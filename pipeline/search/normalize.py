"""Text normalisation shared by the search pack and the app.

The phone normalises a query exactly the way this module normalises place
names and addresses, so "E 14th St" finds "East 14th Street". The Kotlin
twin is core/search SearchText.kt; both are checked against
normalize_cases.json, so change all three together.
"""

import re
import unicodedata

# Long form -> the short form both sides store and search.
ABBREVIATIONS = {
    "street": "st",
    "avenue": "ave",
    "av": "ave",
    "boulevard": "blvd",
    "road": "rd",
    "place": "pl",
    "drive": "dr",
    "lane": "ln",
    "court": "ct",
    "parkway": "pkwy",
    "expressway": "expy",
    "highway": "hwy",
    "terrace": "ter",
    "square": "sq",
    "plaza": "plz",
    "turnpike": "tpke",
    "east": "e",
    "west": "w",
    "north": "n",
    "south": "s",
    "saint": "st",
    "mount": "mt",
    "fort": "ft",
    "building": "bldg",
    "center": "ctr",
    "centre": "ctr",
}

ORDINAL = re.compile(r"\b(\d+)(st|nd|rd|th)\b")
# The straight apostrophe, the right and left single quotes, and the modifier letter apostrophe.
APOSTROPHES = ("'", "\u2019", "\u2018", "\u02bc")
NOT_WORD = re.compile(r"[^0-9a-z]+")
# "123", "123A", "12-34" (Queens), "123 1/2": the leading number, and the
# second half of a hyphenated one.
HOUSE_NUMBER = re.compile(r"^\s*(\d+)(?:\s*-\s*(\d+))?")


def normalize(text: str) -> str:
    """Lower-case, strip accents, drop punctuation and shorten common words.

    Returns space-separated tokens with no leading or trailing space.
    """
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c))
    # Apostrophes go, the typed one and the curly ones OSM editors type ("McDonald’s"), so "mcdonalds" finds both.
    text = text.lower().replace("&", " and ")
    for apostrophe in APOSTROPHES:
        text = text.replace(apostrophe, "")
    text = NOT_WORD.sub(" ", text)
    text = ORDINAL.sub(r"\1", text)
    return " ".join(ABBREVIATIONS.get(token, token) for token in text.split())


def house_number_key(number: str):
    """A sortable integer for a house number, or None if it has no leading digits.

    "123" and "123A" -> 123; Queens' "12-34" -> 120034, so numbers on one
    block sort together and "nearest number" stays on the block; "123 1/2"
    -> 123. The Kotlin twin is AddressQuery.numberKey; both pass
    house_number_cases.json.
    """
    match = HOUSE_NUMBER.match(number or "")
    if not match:
        return None
    first = int(match.group(1))
    if match.group(2) is not None:
        return first * 10_000 + int(match.group(2)) % 10_000
    return first
