"""
AMAP (Amap/Gaode Map) API client for navigation route planning.
"""

import os
import logging
from typing import Optional, Dict, Any, List
from dataclasses import dataclass

import requests

logger = logging.getLogger(__name__)


@dataclass
class NavigationStep:
    """Single navigation step instruction."""
    instruction: str
    distance: int  # meters
    duration: int  # seconds
    action: str  # turn_left, turn_right, go_straight, etc.
    road_name: str = ""


@dataclass
class NavigationRoute:
    """Complete navigation route."""
    steps: List[NavigationStep]
    total_distance: int  # meters
    total_duration: int  # seconds
    start_point: Dict[str, float]  # {lon, lat}
    end_point: Dict[str, float]  # {lon, lat}


class AmapClient:
    """
    Amap (Gaode Maps) API client for navigation services.

    Supports:
    - Walking route planning
    - POI search
    - Geocoding
    """

    # API endpoints
    BASE_URL = "https://restapi.amap.com"
    WALKING_ROUTE_URL = f"{BASE_URL}/v3/direction/walking"
    GEOCODING_URL = f"{BASE_URL}/v3/geocode/geo"
    REVERSE_GEOCODING_URL = f"{BASE_URL}/v3/geocode/regeo"
    TEXT_SEARCH_URL = f"{BASE_URL}/v5/place/text"

    def __init__(self, api_key: Optional[str] = None):
        """
        Initialize Amap client.

        Args:
            api_key: Amap API key. If None, reads from AMAP_API_KEY env var.
        """
        self.api_key = api_key or os.getenv("AMAP_API_KEY", "")
        if not self.api_key:
            logger.warning("AMAP_API_KEY not set, API calls will fail")

        self.session = requests.Session()
        self.session.params = {"key": self.api_key}

    def plan_walking_route(
        self,
        origin: Dict[str, float],
        destination: Dict[str, float]
    ) -> Optional[NavigationRoute]:
        """
        Plan a walking route between two points.

        Args:
            origin: Starting point {lon: x, lat: y} or {longitude: x, latitude: y}
            destination: Ending point {lon: x, lat: y} or {longitude: x, latitude: y}

        Returns:
            NavigationRoute with detailed steps, or None if failed.
        """
        # Normalize coordinate keys
        origin_lon = origin.get("lon") or origin.get("longitude")
        origin_lat = origin.get("lat") or origin.get("latitude")
        dest_lon = destination.get("lon") or destination.get("longitude")
        dest_lat = destination.get("lat") or destination.get("latitude")

        if not all([origin_lon, origin_lat, dest_lon, dest_lat]):
            logger.error("Invalid coordinates: missing lon/lat or longitude/latitude")
            return None

        params = {
            "origin": f"{origin_lon},{origin_lat}",
            "destination": f"{dest_lon},{dest_lat}"
        }

        try:
            response = self.session.get(
                self.WALKING_ROUTE_URL,
                params=params,
                timeout=10
            )
            response.raise_for_status()
            data = response.json()

            if data.get("status") != "1":
                error_info = data.get("info", "Unknown error")
                error_code = data.get("infocode", "N/A")
                logger.error(f"Amap API error: {error_info} (code: {error_code})")
                return None

            return self._parse_route(data.get("route", {}))

        except requests.RequestException as e:
            logger.error(f"Failed to call Amap walking route API: {e}")
            return None

    def search_poi(
        self,
        keywords: str,
        city: Optional[str] = None,
        location: Optional[Dict[str, float]] = None
    ) -> List[Dict[str, Any]]:
        """
        Search for Points of Interest (POI).

        Args:
            keywords: Search keywords (e.g., "餐厅", "肯德基")
            city: City name to limit search
            location: Search center {lon, lat}

        Returns:
            List of POI dictionaries with name, location, address, etc.
        """
        params = {
            "keywords": keywords,
            "types": "",  # Empty to search all types
        }

        if city:
            params["city"] = city
        if location:
            params["location"] = f"{location['lon']},{location['lat']}"

        try:
            response = self.session.get(
                self.TEXT_SEARCH_URL,
                params=params,
                timeout=10
            )
            response.raise_for_status()
            data = response.json()

            if data.get("status") != "1":
                return []

            pois = data.get("pois", [])
            results = []
            for poi in pois[:10]:  # Limit to top 10
                results.append({
                    "name": poi.get("name"),
                    "address": poi.get("address"),
                    "location": self._parse_location(poi.get("location")),
                    "distance": poi.get("distance"),
                    "type": poi.get("type"),
                })

            return results

        except requests.RequestException as e:
            logger.error(f"Failed to search POI: {e}")
            return []

    def search_nearby(
        self,
        keywords: str,
        location: Dict[str, float],
        radius: int = 1000
    ) -> List[Dict[str, Any]]:
        """
        Search for nearby POIs around a location.

        Args:
            keywords: Search keywords (e.g., "肯德基", "餐厅")
            location: Center location {lon, lat}
            radius: Search radius in meters (default 1000m)

        Returns:
            List of nearby POIs, sorted by distance
        """
        return self.search_poi(keywords=keywords, location=location)

    def geocode(self, address: str, city: Optional[str] = None) -> Optional[Dict[str, float]]:
        """
        Convert address to coordinates.

        Args:
            address: Address string
            city: City name for more accurate results

        Returns:
            {lon, lat} or None if failed
        """
        params = {"address": address}
        if city:
            params["city"] = city

        try:
            response = self.session.get(
                self.GEOCODING_URL,
                params=params,
                timeout=10
            )
            response.raise_for_status()
            data = response.json()

            if data.get("status") != "1" or not data.get("geocodes"):
                return None

            return self._parse_location(data["geocodes"][0].get("location"))

        except requests.RequestException as e:
            logger.error(f"Failed to geocode address: {e}")
            return None

    def reverse_geocode(self, lon: float, lat: float) -> Optional[str]:
        """
        Convert coordinates to address.

        Args:
            lon: Longitude
            lat: Latitude

        Returns:
            Formatted address string or None if failed
        """
        try:
            response = self.session.get(
                self.REVERSE_GEOCODING_URL,
                params={"location": f"{lon},{lat}"},
                timeout=10
            )
            response.raise_for_status()
            data = response.json()

            if data.get("status") != "1":
                return None

            return data.get("regeocode", {}).get("formatted_address")

        except requests.RequestException as e:
            logger.error(f"Failed to reverse geocode: {e}")
            return None

    def _parse_route(self, route_data: Dict[str, Any]) -> NavigationRoute:
        """Parse Amap route response into NavigationRoute."""
        steps = []
        total_distance = 0
        total_duration = 0

        # Get the first (best) route
        paths = route_data.get("paths", [])
        if not paths:
            return NavigationRoute(steps=[], total_distance=0, total_duration=0,
                                   start_point={}, end_point={})

        path = paths[0]
        total_distance = int(path.get("distance", 0))
        total_duration = int(path.get("duration", 0))

        # Parse steps
        for step_data in path.get("steps", []):
            step = NavigationStep(
                instruction=step_data.get("instruction", ""),
                distance=int(step_data.get("distance", 0)),
                duration=int(step_data.get("duration", 0)),
                action=self._extract_action(step_data.get("instruction", "")),
                road_name=step_data.get("road", "")
            )
            steps.append(step)

        return NavigationRoute(
            steps=steps,
            total_distance=total_distance,
            total_duration=total_duration,
            start_point={},  # Amap doesn't return this separately
            end_point={}
        )

    def _parse_location(self, location_str: Optional[str]) -> Dict[str, float]:
        """Parse location string 'lon,lat' into dict."""
        if not location_str:
            return {}
        try:
            lon, lat = location_str.split(",")
            return {"lon": float(lon), "lat": float(lat)}
        except (ValueError, AttributeError):
            return {}

    def _extract_action(self, instruction: str) -> str:
        """Extract navigation action from instruction text."""
        instruction_lower = instruction.lower()

        if any(word in instruction_lower for word in ["左转", "向左"]):
            return "turn_left"
        elif any(word in instruction_lower for word in ["右转", "向右"]):
            return "turn_right"
        elif any(word in instruction_lower for word in ["直行", "向前", "继续"]):
            return "go_straight"
        elif any(word in instruction_lower for word in ["掉头", "向后转"]):
            return "u_turn"
        elif any(word in instruction_lower for word in ["进入", "上"]):
            return "enter"
        else:
            return "unknown"


# Singleton instance for reuse
_amap_client: Optional[AmapClient] = None


def get_amap_client() -> AmapClient:
    """Get or create singleton Amap client instance."""
    global _amap_client
    if _amap_client is None:
        _amap_client = AmapClient()
    return _amap_client
