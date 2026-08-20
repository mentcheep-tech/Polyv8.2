# PolymarketClient v0.8.5

Android prototype for live Polymarket BTC 5-minute market monitoring and explicitly armed CLOB V2 execution.

## Live execution

This version implements production CLOB V2 order signing and `POST /order` for EOA wallets. Live execution is **not armed on app startup**.

To enable it:
1. Open **CLOB KEYS**.
2. Authenticate with the wallet private key. The private key and returned L2 credentials are stored encrypted using Android Keystore.
3. Use **ENABLE LIVE EXECUTION (SESSION)** and confirm.
4. Manual BUY YES / BUY NO buttons then show a real-order confirmation before submitting a FOK market BUY.
5. In **AUTOMATION**, separately choose the side/amount/cooldown and explicitly **ARM LIVE AUTOMATION**. Automation submits only when all configured strategy requirements are met, the market is inside the time window, and the current ask-side book has enough liquidity for the configured amount.

The app uses the current Polymarket CLOB V2 order format: EIP-712 Exchange domain version 2, V2 order fields (`timestamp`, `metadata`, `builder`), L2 HMAC request authentication, and `POST /order` with FOK order type.

**Use a small funded wallet for initial testing. Verify the selected market, side, amount, and strategy parameters before enabling live automation.**

## Build

Codemagic workflow: `android-debug`. The workflow uses the cloud Gradle installation and builds `assembleDebug`.


## v0.8.6 market rotation
The live engine now selects active BTC markets by actual ~5-minute market duration (4–6 minutes) or explicit 5-minute labeling, regardless of remaining time. It no longer waits for the 180-second strategy window to discover a market. The engine automatically scans again when the selected market ends or is no longer a 5-minute market.
