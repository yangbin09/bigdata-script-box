// utils/excelPaste.js — parse TSV (Excel/Numbers/Sheets clipboard) and propose
// column→param mappings for the batch table editor.
//
// Behaviour:
//  * Read clipboardData.getData('text/plain'), split on \n / \r, parse each
//    row with split('\t'). Tolerant of trailing blank rows.
//  * First row is treated as a header and matched against script params by
//    case-insensitive substring (label > name > fallback). Users can override
//    the mapping in ColumnMappingDialog before commit.
//  * If no header is detected (single row, no tabs at all), fall through and
//    let the caller decide what to do.

/**
 * @param {string} raw - clipboard text
 * @returns {{ headers: string[], rows: string[][], headerDetected: boolean }}
 */
export function parseTsv(raw) {
  if (!raw) return { headers: [], rows: [], headerDetected: false }
  const lines = raw.split(/\r?\n/).filter((l) => l.length > 0)
  if (!lines.length) return { headers: [], rows: [], headerDetected: false }

  const split = (l) => l.split('\t').map((c) => c.trim())
  const first = split(lines[0])

  // Heuristic: header detected if all cells are short (<= 64 chars) and at
  // least one contains a letter (avoids mistaking single-cell paste for a
  // header). Skip "no header" detection when there's just one column (TSV
  // from a single column is indistinguishable from a values-only dump).
  const headerDetected =
    first.length > 1 &&
    first.every((c) => c.length <= 64) &&
    first.some((c) => /[A-Za-z一-龥_]/.test(c))

  if (headerDetected) {
    const rows = lines.slice(1).map(split)
    return { headers: first, rows, headerDetected: true }
  }
  // No header — treat first row as a single value row.
  return { headers: [], rows: lines.map(split), headerDetected: false }
}

/**
 * Given script params and clipboard headers, suggest a column→param map.
 *
 * @param {string[]} headers
 * @param {Array<{name: string, label?: string}>} params
 * @returns {Array<{column: string, param: string|null}>}
 */
export function suggestMapping(headers, params) {
  if (!headers?.length || !params?.length) {
    return headers?.map((column) => ({ column, param: null })) ?? []
  }
  const norm = (s) => (s || '').toString().trim().toLowerCase()
  return headers.map((column) => {
    const c = norm(column)
    // 1) exact label match (case-insensitive)
    let hit = params.find((p) => norm(p.label) === c)
    if (hit) return { column, param: hit.name }
    // 2) exact name match
    hit = params.find((p) => norm(p.name) === c)
    if (hit) return { column, param: hit.name }
    // 3) substring match: header contains name OR name contains header
    hit = params.find((p) => c.includes(norm(p.name)) || norm(p.name).includes(c))
    if (hit) return { column, param: hit.name }
    // 4) substring match on label
    hit = params.find((p) => norm(p.label) && (c.includes(norm(p.label)) || norm(p.label).includes(c)))
    if (hit) return { column, param: hit.name }
    return { column, param: null }
  })
}

/**
 * Apply a mapping to TSV rows → list of plain objects keyed by param name.
 * Rows that resolve to no columns (or unmapped columns only) are still
 * returned so the caller can highlight them as empty.
 *
 * @param {string[][]} rows
 * @param {Array<{column: string, param: string|null}>} mapping
 * @returns {Array<Record<string, string>>}
 */
export function applyMapping(rows, mapping) {
  const colIdx = mapping.map((m) => m.param) // param per column
  return rows.map((row) => {
    const obj = {}
    row.forEach((cell, i) => {
      const key = colIdx[i]
      if (key) obj[key] = cell
    })
    return obj
  })
}
