import { ElMessage } from 'element-plus'

/**
 * Clipboard + text-download helpers.
 *
 * Previously hand-rolled three times (HistoryView, ExecutionResultPanel,
 * LogPane) with the same Blob/createObjectURL dance. The revoke now happens on
 * the next tick: revoking synchronously right after `click()` is unreliable in
 * some browsers and can drop the download.
 */

async function writeClipboard(text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch (_) { /* fall through to the legacy path */ }
  // Insecure origin (plain http on a LAN address) has no async clipboard API.
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch (_) {
    return false
  }
}

/** Copy `text` and toast the outcome. Returns whether the copy succeeded. */
export async function copyText(text, emptyMessage = '没有内容可以复制', okMessage = '已复制') {
  if (!text) {
    ElMessage.warning(emptyMessage)
    return false
  }
  const ok = await writeClipboard(String(text))
  if (ok) ElMessage.success(okMessage)
  else ElMessage.error('复制失败')
  return ok
}

/** Trigger a client-side text download. */
export function downloadText(filename, text, emptyMessage = '没有内容可以下载') {
  if (!text) {
    ElMessage.warning(emptyMessage)
    return false
  }
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  // Give the browser a tick to start the download before releasing the blob.
  setTimeout(() => URL.revokeObjectURL(url), 0)
  return true
}
