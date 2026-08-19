/**
 * Coin groups for the "Σ all coins" modal: every market's base asset (the part before the
 * dash, so BTC-EUR and BTC-USDC are both BTC) belongs to exactly one group. The lists below
 * are the defaults; a coin can be moved to another group from the modal (the select in its
 * row), which is remembered in localStorage and wins over this file. Anything not listed
 * here is "Other".
 *
 * The assignments are a judgment call (what is "alpha", when is a coin "dead") — edit freely.
 */
export const CATEGORIES = [
  { id: 'stable', label: 'Stablecoins', hint: 'pegged to a fiat currency; no price movement to trade' },
  { id: 'alpha', label: 'Alpha', hint: 'majors / blue chips: the biggest coins by market cap' },
  { id: 'layer', label: 'L1 / L2 & infra', hint: 'smart-contract chains, rollups, bridges and other base infrastructure' },
  { id: 'defi', label: 'DeFi', hint: 'exchanges, lending, staking, oracles, RWA' },
  { id: 'ai', label: 'AI / DePIN', hint: 'AI agents, compute, storage, data and physical networks' },
  { id: 'gaming', label: 'Gaming / NFT', hint: 'games, metaverse, NFT marketplaces, fan tokens' },
  { id: 'meme', label: 'Meme', hint: 'meme and culture coins' },
  { id: 'dead', label: 'Dead', hint: 'collapsed or abandoned projects' },
  { id: 'other', label: 'Other', hint: 'everything not listed in src/categories.js' },
];
export const DEFAULT_CATEGORY = 'other';

const DEFAULTS = {
  stable: 'USDC EURC EURCV EUROP FRAX USDCV',
  alpha: 'BTC ETH XRP BNB SOL TRX ADA HYPE LINK XLM BCH AVAX SUI LTC HBAR DOT TAO UNI AAVE NEAR ETC APT POL ICP',
  layer:
    'ALGO ATOM EGLD FIL FLR INJ SEI TIA STX ARB OP STRK ZK LINEA METIS MOVR GLMR ASTR CELO KAVA OSMO XTZ NEO GAS ONT ONG ' +
    'ICX IOST ZIL VET VTHO XDC CSPR KSM ROSE SKL CTC AXL ZRO W DYM SAGA MANTA TAIKO SCR ZETA MON S KAIA MOVE INIT SOON ' +
    '0G XPL PLUME MEGA SOMI PEAQ MIOTA XNO LSK STRAX DGB RVN XVG QKC ZEN DUSK CFG CRO AKT ALT LUMIA MERL BOB CYBER NIL ' +
    'ZAMA AZTEC IKA NEWT ZKC ZKJ PROVE ESP KAS CHR WAXP A G ZRC LRC BICO SYN DBR ACX CELR CTSI MANTRA ZEUS PARTI WCT ' +
    'FUEL QNT BABY LAYER ANKR ARK TOMO BTT COTI HOT EPIC PROM CC ZIG',
  defi:
    'SKY SPK CRV CVX COMP SNX YFI SUSHI CAKE 1INCH DYDX GMX GNS BNT KNC ZRX DODO WOO COW AERO VELO THE MAV HFT DRIFT ' +
    'JUP RAY ORCA KMNO JTO LDO RPL EIGEN ETHFI REZ SWELL SSV KERNEL PUFFER SOLV LISTA SYRUP MORPHO FLUID EUL PENDLE ' +
    'ENA ONDO CPOOL HUMA LQTY RSR GNO SAFE PYTH API3 BAND DIA UMA TRB RED BANANA ASTER LIGHTER AVNT HOME EDEN YB ENSO ' +
    'MMT DEEP CETUS HAEDAL STO F T CAP AEVO BLEND CTK TWT SFP AMP FIDA RUNE WLFI MLN BEL C98 OGN REQ ACH MTL',
  ai:
    'RENDER FET AGI AI AIOZ IO NOS FLOCK FLUX GRASS PROMPT VIRTUAL AIXBT KAITO ARKM WLD NMR RLC PHA VANA SAPIEN RECALL ' +
    'SHELL SOSO KITE MIRA OPEN TAI SQD SXT C ICNT VVV SENT ATH CARV STORJ GRT HNT LPT GLM POND IRYS WAL XYO WMTX ' +
    'JASMY LMWR 2Z ROBO ARPA TRAC',
  gaming:
    'AXS SLP SAND MANA GALA ENJ ILV PYR MAGIC PIXEL PORTAL BIGTIME YGG XAI SUPER ALICE TLM MAVIA CATI GMT BEAM RON ' +
    'CROSS ACE CHZ FORM IMX ME TNSR BLUR RARE AUCTION APE VANRY MOCA SWEAT FUN WIN AGLD',
  meme:
    'DOGE SHIB PEPE FLOKI BONK WIF BRETT POPCAT MEW MOG PNUT GOAT TURBO NEIRO BOME MOODENG CHILLGUY FARTCOIN SPX TOSHI ' +
    'PONKE DEGEN PEOPLE TRUMP PUMP USELESS GIGA NPC CAT MEME ANIME PENGU NOT HMSTR DOGS ACT',
  dead: 'LUNA LUNA2 FTT',
};

const DEFAULT_OF = new Map();
for (const [id, list] of Object.entries(DEFAULTS)) for (const base of list.split(/\s+/)) if (base) DEFAULT_OF.set(base, id);

const STORAGE_KEY = 'coinCategories';
/** Base asset of a market: 'BTC-EUR' → 'BTC'. */
export const baseOf = (market) => market.split('-')[0];

/** The user's own assignments (base asset → category id), kept in localStorage. */
export function loadOverrides() {
  try {
    const o = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}');
    return o && typeof o === 'object' ? o : {};
  } catch { return {}; }
}
export function saveOverrides(overrides) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(overrides));
}

/** Category id of a market; `overrides` win over the defaults in this file. */
export function categoryOf(market, overrides = {}) {
  const base = baseOf(market);
  const id = overrides[base] ?? DEFAULT_OF.get(base) ?? DEFAULT_CATEGORY;
  return CATEGORIES.some((c) => c.id === id) ? id : DEFAULT_CATEGORY;
}
