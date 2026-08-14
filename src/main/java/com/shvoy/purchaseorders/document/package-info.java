/**
 * Story 4.6's document generation: a clean data representation of a
 * finalised PO ({@link com.shvoy.purchaseorders.document.PurchaseOrderDocumentData})
 * and the renderer that turns it into a PDF ({@link
 * com.shvoy.purchaseorders.document.PurchaseOrderDocumentRenderer}) —
 * deliberately its own package, separate from {@code dto} (the JSON API
 * response shapes) and {@code service} (the business logic that assembles
 * this data), so the rendering concern has its own clear seam. See that
 * renderer's Javadoc for why.
 */
package com.shvoy.purchaseorders.document;
