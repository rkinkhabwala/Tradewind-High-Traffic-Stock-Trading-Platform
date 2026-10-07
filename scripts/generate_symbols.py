#!/usr/bin/env python3
"""Generates the 500-symbol seed migration for marketdata-service (spec section 9, Phase 1 seed).

Deterministic (fixed RNG seed): re-running produces the same file.
Usage: python3 scripts/generate_symbols.py > marketdata-service/src/main/resources/db/migration/V2__seed_symbols.sql
"""
import random

# Well-known tickers with plausible starting prices and annualised volatility. Prices are simulated.
KNOWN = [
    ("AAPL", "Apple Inc.", "Information Technology", "230.00", "0.28"),
    ("MSFT", "Microsoft Corp.", "Information Technology", "420.00", "0.25"),
    ("GOOGL", "Alphabet Inc.", "Communication Services", "170.00", "0.30"),
    ("AMZN", "Amazon.com Inc.", "Consumer Discretionary", "185.00", "0.32"),
    ("NVDA", "NVIDIA Corp.", "Information Technology", "120.00", "0.55"),
    ("META", "Meta Platforms Inc.", "Communication Services", "520.00", "0.38"),
    ("TSLA", "Tesla Inc.", "Consumer Discretionary", "250.00", "0.60"),
    ("JPM", "JPMorgan Chase & Co.", "Financials", "210.00", "0.22"),
    ("V", "Visa Inc.", "Financials", "280.00", "0.20"),
    ("JNJ", "Johnson & Johnson", "Health Care", "155.00", "0.17"),
    ("WMT", "Walmart Inc.", "Consumer Staples", "80.00", "0.18"),
    ("PG", "Procter & Gamble Co.", "Consumer Staples", "165.00", "0.16"),
    ("MA", "Mastercard Inc.", "Financials", "470.00", "0.21"),
    ("UNH", "UnitedHealth Group Inc.", "Health Care", "520.00", "0.24"),
    ("HD", "Home Depot Inc.", "Consumer Discretionary", "380.00", "0.22"),
    ("XOM", "Exxon Mobil Corp.", "Energy", "115.00", "0.26"),
    ("CVX", "Chevron Corp.", "Energy", "155.00", "0.25"),
    ("KO", "Coca-Cola Co.", "Consumer Staples", "65.00", "0.15"),
    ("PEP", "PepsiCo Inc.", "Consumer Staples", "170.00", "0.16"),
    ("COST", "Costco Wholesale Corp.", "Consumer Staples", "880.00", "0.20"),
    ("DIS", "Walt Disney Co.", "Communication Services", "95.00", "0.30"),
    ("NFLX", "Netflix Inc.", "Communication Services", "650.00", "0.40"),
    ("ADBE", "Adobe Inc.", "Information Technology", "520.00", "0.35"),
    ("CRM", "Salesforce Inc.", "Information Technology", "280.00", "0.35"),
    ("INTC", "Intel Corp.", "Information Technology", "22.00", "0.45"),
    ("AMD", "Advanced Micro Devices Inc.", "Information Technology", "150.00", "0.55"),
    ("ORCL", "Oracle Corp.", "Information Technology", "160.00", "0.30"),
    ("CSCO", "Cisco Systems Inc.", "Information Technology", "55.00", "0.22"),
    ("PFE", "Pfizer Inc.", "Health Care", "28.00", "0.24"),
    ("NKE", "Nike Inc.", "Consumer Discretionary", "80.00", "0.30"),
    ("MCD", "McDonald's Corp.", "Consumer Discretionary", "290.00", "0.17"),
    ("BA", "Boeing Co.", "Industrials", "170.00", "0.38"),
    ("GE", "General Electric Co.", "Industrials", "170.00", "0.30"),
    ("IBM", "International Business Machines Corp.", "Information Technology", "210.00", "0.22"),
    ("T", "AT&T Inc.", "Communication Services", "22.00", "0.20"),
    ("VZ", "Verizon Communications Inc.", "Communication Services", "42.00", "0.18"),
    ("GME", "GameStop Corp.", "Consumer Discretionary", "25.00", "0.95"),
    ("AMC", "AMC Entertainment Holdings Inc.", "Communication Services", "5.00", "1.00"),
    ("PLTR", "Palantir Technologies Inc.", "Information Technology", "30.00", "0.65"),
    ("UBER", "Uber Technologies Inc.", "Industrials", "75.00", "0.40"),
    ("SBUX", "Starbucks Corp.", "Consumer Discretionary", "95.00", "0.27"),
    ("NEE", "NextEra Energy Inc.", "Utilities", "75.00", "0.22"),
]

SECTORS = ["Information Technology", "Health Care", "Financials", "Consumer Discretionary",
           "Communication Services", "Industrials", "Consumer Staples", "Energy", "Utilities",
           "Real Estate", "Materials"]
FIRST = ["Apex", "Harbor", "Summit", "Blue", "Iron", "Silver", "Granite", "Northern", "Pacific", "Atlas",
         "Cobalt", "Crescent", "Evergreen", "Falcon", "Golden", "Horizon", "Keystone", "Liberty", "Meridian",
         "Nova", "Orion", "Pioneer", "Quantum", "Redwood", "Sterling", "Titan", "Union", "Vertex", "Willow", "Zenith"]
SECOND = ["Systems", "Dynamics", "Networks", "Therapeutics", "Energy", "Logistics", "Foods", "Materials",
          "Capital", "Robotics", "Semiconductors", "Biosciences", "Media", "Retail", "Utilities", "Realty",
          "Aerospace", "Software", "Motors", "Pharma"]
SUFFIX = ["Inc.", "Corp.", "Holdings", "Group", "Ltd.", "Co."]
TOTAL = 500


def sql_text(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def main() -> None:
    rng = random.Random(20261007)
    rows = list(KNOWN)
    used = {row[0] for row in rows}
    names = {row[1] for row in rows}
    while len(rows) < TOTAL:
        ticker = "".join(rng.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZ") for _ in range(rng.choice([3, 4])))
        name = f"{rng.choice(FIRST)} {rng.choice(SECOND)} {rng.choice(SUFFIX)}"
        if ticker in used or name in names:
            continue
        used.add(ticker)
        names.add(name)
        # Log-uniform starting price $3-$600; annualised volatility 15%-80%.
        price = 3 * (200 ** rng.random())
        volatility = 0.15 + 0.65 * rng.random() ** 1.5
        rows.append((ticker, name, rng.choice(SECTORS), f"{price:.2f}", f"{volatility:.4f}"))

    print("-- Generated by scripts/generate_symbols.py. Do not edit by hand.")
    print("INSERT INTO symbols (symbol, name, sector, base_price, volatility) VALUES")
    print(",\n".join(f"({sql_text(s)}, {sql_text(n)}, {sql_text(sec)}, {p}, {v})" for s, n, sec, p, v in rows) + ";")


if __name__ == "__main__":
    main()
