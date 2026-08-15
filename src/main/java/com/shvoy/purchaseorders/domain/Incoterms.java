package com.shvoy.purchaseorders.domain;

/**
 * The Incoterms 2020 delivery terms (PO-issuance gate) — the agreed
 * responsibility split between buyer and supplier, required on a finalised PO
 * and shown on its document. Stored as a string.
 */
public enum Incoterms {
    EXW, FCA, FAS, FOB, CFR, CIF, CPT, CIP, DAP, DPU, DDP
}
