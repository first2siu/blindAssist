"""Deduplication manager for obstacle announcements."""

import asyncio
import time
from collections import defaultdict
from typing import Dict, List, Optional, Tuple


class ObstacleDedupManager:
    """
    Manages deduplication of obstacle announcements to avoid repeated warnings.

    Features:
    - Cooldown period for same obstacle type in same position
    - Distance-based filtering (only announce when getting closer)
    - State tracking (new/ongoing/cleared)
    """

    def __init__(
        self,
        cooldown_seconds: int = 10,
        min_distance_change: float = 2.0,
        max_cache_size: int = 100
    ):
        """
        Initialize deduplication manager.

        Args:
            cooldown_seconds: Minimum seconds between same type of announcement
            min_distance_change: Minimum distance change (meters) to re-announce
            max_cache_size: Maximum number of tracked obstacles per user
        """
        self.cooldown_seconds = cooldown_seconds
        self.min_distance_change = min_distance_change
        self.max_cache_size = max_cache_size

        # Track announced obstacles: user_id -> [(obstacle_key, timestamp, distance)]
        self._announced: Dict[str, List[Tuple[str, float, float]]] = defaultdict(list)

        # Track cleared obstacles for "clear" announcements
        self._tracked: Dict[str, Dict[str, float]] = defaultdict(dict)

    def should_announce(
        self,
        user_id: str,
        obstacle_type: str,
        position: str,
        distance: float,
        urgency: str
    ) -> Tuple[bool, str]:
        """
        Determine if an obstacle should be announced.

        Args:
            user_id: User identifier
            obstacle_type: Type of obstacle (e.g., "台阶", "车辆")
            position: Position relative to user (e.g., "前方", "左侧")
            distance: Estimated distance in meters
            urgency: Urgency level (critical/high/medium/low)

        Returns:
            Tuple of (should_announce, reason)
        """
        current_time = time.time()
        key = self._make_key(obstacle_type, position)

        # Critical urgency always bypasses deduplication (with shorter cooldown)
        if urgency == "critical":
            last_announced = self._get_last_announced(user_id, key)
            if last_announced and (current_time - last_announced[1]) < 5:
                return False, "critical_recently_announced"
            return True, "critical_new"

        # Check if recently announced
        announced = self._announced[user_id]
        for announced_key, announced_time, announced_distance in announced:
            if announced_key == key:
                time_since = current_time - announced_time

                # Within cooldown period
                if time_since < self.cooldown_seconds:
                    # But significantly closer - worth re-announcing
                    if announced_distance - distance > self.min_distance_change:
                        self._update_announcement(user_id, key, current_time, distance)
                        return True, "getting_closer"
                    return False, "within_cooldown"

        # New obstacle or cooldown expired
        self._update_announcement(user_id, key, current_time, distance)
        return True, "new_obstacle"

    def clear_obstacle(self, user_id: str, obstacle_type: str, position: str):
        """Remove obstacle from tracking (e.g., user passed it)."""
        key = self._make_key(obstacle_type, position)
        self._tracked[user_id].pop(key, None)

        # Also remove from announced if exists
        self._announced[user_id] = [
            (k, t, d) for k, t, d in self._announced[user_id]
            if k != key
        ]

    def cleanup_old_entries(self, user_id: str, max_age_seconds: int = 60):
        """Remove old entries to prevent memory growth."""
        current_time = time.time()
        cutoff = current_time - max_age_seconds

        self._announced[user_id] = [
            (k, t, d) for k, t, d in self._announced[user_id]
            if t > cutoff
        ]

        # Trim to max size
        if len(self._announced[user_id]) > self.max_cache_size:
            self._announced[user_id] = self._announced[user_id][-self.max_cache_size:]

    def _make_key(self, obstacle_type: str, position: str) -> str:
        """Create a unique key for an obstacle."""
        return f"{obstacle_type}:{position}"

    def _get_last_announced(self, user_id: str, key: str) -> Optional[Tuple[str, float, float]]:
        """Get the last announcement for a specific obstacle key."""
        for announced_key, announced_time, announced_distance in self._announced[user_id]:
            if announced_key == key:
                return (announced_key, announced_time, announced_distance)
        return None

    def _update_announcement(self, user_id: str, key: str, timestamp: float, distance: float):
        """Update or add an announcement record."""
        # Remove existing entry if any
        self._announced[user_id] = [
            (k, t, d) for k, t, d in self._announced[user_id]
            if k != key
        ]

        # Add new entry
        self._announced[user_id].append((key, timestamp, distance))

        # Cleanup periodically
        if len(self._announced[user_id]) > self.max_cache_size:
            self.cleanup_old_entries(user_id)


# Global instance
_dedup_manager = ObstacleDedupManager()


def get_dedup_manager() -> ObstacleDedupManager:
    """Get the global deduplication manager instance."""
    return _dedup_manager
