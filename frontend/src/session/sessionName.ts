export function suggestedSessionName(now = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `Session ${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}-${pad(now.getMinutes())}`
}

/** Helpful early feedback; the backend remains authoritative and input is never rewritten. */
export function sessionNameError(name: string): string | null {
  if (!name.trim() || name === '.' || name === '..') return 'Enter a Session name.'
  if (name.length > 249 || new TextEncoder().encode(name).length > 249) return 'Use at most 249 UTF-8 bytes and 249 characters.'
  if (/[/\\<>:"|?*]/u.test(name) || [...name].some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) >= 127 && character.charCodeAt(0) <= 159) || /[ .]$/.test(name)) return 'Avoid control characters, / \\ < > : " | ? *, and a final space or period.'
  if (/^(CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|(?:COM|LPT)[1-9¹²³])$/iu.test(name.split('.')[0].trimEnd())) return 'Choose a name other than a reserved Windows device name.'
  for (let i = 0; i < name.length; i++) {
    const code = name.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = name.charCodeAt(++i)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return 'Enter valid Unicode text.'
    } else if (code >= 0xdc00 && code <= 0xdfff) return 'Enter valid Unicode text.'
  }
  return null
}
