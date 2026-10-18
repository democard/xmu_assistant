"""Intersection of two great-circle distance circles on a spherical Earth.

This is a geometric model, not an assertion about the attendance server's
distance metric. Degenerate centers have no unique pair and return None.
"""

import math

EARTH_RADIUS_METERS = 6371000.0
_ANGLE_EPS = 1e-12


def solve_spherical_candidates(lat1, lon1, lat2, lon2, distance1, distance2):
    """Return two (latitude, longitude) candidates; tangencies repeat one point.

Distances are nonnegative meters and cannot exceed half Earth's circumference.
Coordinates use degrees; longitude is normalized in the returned candidates.
Invalid inputs, impossible circles, coincident and antipodal centers return None.
"""
    values = (lat1, lon1, lat2, lon2, distance1, distance2)
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) for v in values):
        return None
    try:
        if not all(math.isfinite(v) for v in values):
            return None
    except OverflowError:
        # Python integers may exceed even the largest finite double.
        return None
    if abs(lat1) > 90 or abs(lat2) > 90:
        return None
    if not all(0 <= d <= math.pi * EARTH_RADIUS_METERS for d in (distance1, distance2)):
        return None

    def vector(lat, lon):
        phi, lam = math.radians(lat), math.radians(lon % 360)
        return (math.cos(phi) * math.cos(lam), math.cos(phi) * math.sin(lam), math.sin(phi))

    a, b = vector(lat1, lon1), vector(lat2, lon2)
    cross = (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    sin_delta = math.hypot(*cross)
    cos_delta = max(-1.0, min(1.0, sum(x * y for x, y in zip(a, b))))
    delta = math.atan2(sin_delta, cos_delta)
    if sin_delta <= _ANGLE_EPS:
        return None
    r1, r2 = distance1 / EARTH_RADIUS_METERS, distance2 / EARTH_RADIUS_METERS
    # Spherical triangle inequalities, including circles larger than hemispheres.
    margins = (r1 + r2 - delta, delta - abs(r1 - r2), 2 * math.pi - r1 - r2 - delta)
    if min(margins) < -_ANGLE_EPS:
        return None
    normal = tuple(v / sin_delta for v in cross)
    tangent = (normal[1] * a[2] - normal[2] * a[1], normal[2] * a[0] - normal[0] * a[2], normal[0] * a[1] - normal[1] * a[0])
    x = math.cos(r1)
    # cos(r2)-cos(delta)*cos(r1), written without subtracting values near 1.
    numerator = 2 * (math.sin(delta / 2) ** 2 - math.sin(r2 / 2) ** 2 + cos_delta * math.sin(r1 / 2) ** 2)
    y = numerator / sin_delta
    sin_r1 = math.sin(r1)
    h2 = (sin_r1 - abs(y)) * (sin_r1 + abs(y))
    # A boundary within angular roundoff is one tangent, rather than a false
    # negative square root or a spurious pair separated by centimeters.
    if min(margins) <= _ANGLE_EPS:
        height = 0.0
    elif h2 < 0:
        return None
    else:
        height = math.sqrt(h2)

    def coordinate(sign):
        point = tuple(x * a[i] + y * tangent[i] + sign * height * normal[i] for i in range(3))
        return (math.degrees(math.atan2(point[2], math.hypot(point[0], point[1]))), math.degrees(math.atan2(point[1], point[0])))

    return coordinate(1), coordinate(-1)
