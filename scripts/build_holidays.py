"""Rebuild offline public-holiday assets with: uv run --script scripts/build_holidays.py"""
# /// script
# dependencies = ["holidays==0.109"]
# ///
import json
from datetime import date
from importlib.metadata import distribution
from pathlib import Path

import holidays

OUT = Path(__file__).resolve().parents[1] / "internal/server/web/holidays"
YEARS = range(2020, 2036)
# State Council annual notices. Include whole breaks and explicitly designated workdays.
# 2026: https://www.beijing.gov.cn/zhengce/zhengcefagui/202511/t20251104_4258873.html
# 2027: https://www.kashi.gov.cn/ksdqxzgs/c115966/202611/510945af03af482a8190dda1a63f5603.shtml
CHINA = {
    2026: ([(1,1,3),(2,15,23),(4,4,6),(5,1,5),(6,19,21),(9,25,27),(10,1,7)],
           ["01-04","02-14","02-28","05-09","09-20","10-10"]),
    2027: ([(1,1,3),(2,5,13),(4,3,5),(5,1,5),(6,9,9),(9,15,15),(10,1,7)],
           ["01-31","02-14","05-08","09-26","10-09"]),
}

def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    countries = sorted(holidays.list_supported_countries(include_aliases=False))
    write("regions.json", countries)
    dist = distribution("holidays")
    license_file = next(f for f in dist.files if f.name == "LICENSE")
    (OUT / "LICENSE.txt").write_bytes(dist.locate_file(license_file).read_bytes())
    for code in countries:
        cal = holidays.country_holidays(code, years=YEARS, language="zh_CN" if code == "CN" else "en_US")
        entries = {str(d): label for d, label in cal.items() if d.year in YEARS}
        if code == "CN":
            for year, (breaks, workdays) in CHINA.items():
                for (month, first, last), name in zip(breaks, ["元旦","春节","清明节","劳动节","端午节","中秋节","国庆节"]):
                    for day in range(first, last+1):
                        entries[str(date(year, month, day))] = name
                for md in workdays:
                    entries[f"{year}-{md}"] = "调休上班"
        write(code + ".json", dict(sorted(entries.items())))
    print(len(countries), "countries/territories")

if __name__ == "__main__":
    main()
