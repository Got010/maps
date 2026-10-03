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
NOT_WORD = re.compile(r"[^0-9a-z]+")


def normalize(text: str) -> str:
    """Lower-case, strip accents, drop punctuation and shorten common words.

    Returns space-separated tokens with no leading or trailing space.
    """
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = text.lower().replace("&", " and ").replace("'", "")
    text = NOT_WORD.sub(" ", text)
    text = ORDINAL.sub(r"\1", text)
    return " ".join(ABBREVIATIONS.get(token, token) for token in text.split())
