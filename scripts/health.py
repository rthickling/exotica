"""Check the running services from the Compose tools container."""

from urllib.request import urlopen

for url in (
    "http://app:8080/actuator/health",
    "http://pricer:8090/health",
    "http://ui/health",
    "http://ui/actuator/health",
):
    with urlopen(url, timeout=5) as response:
        print(url, response.read().decode())
