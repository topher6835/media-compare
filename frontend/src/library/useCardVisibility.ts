import { useCallback, useEffect, useRef, useState } from 'react'

export function useCardVisibility() {
  const cards = useRef(new Map<Element, number>())
  const observer = useRef<IntersectionObserver | null>(null)
  const [visible, setVisible] = useState<Set<number>>(new Set())

  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return
    const current = new IntersectionObserver((entries) => {
      setVisible((previous) => {
        const next = new Set(previous)
        for (const entry of entries) {
          const id = cards.current.get(entry.target)
          if (id === undefined) continue
          if (entry.isIntersecting) next.add(id)
          else next.delete(id)
        }
        return next
      })
    }, { rootMargin: '0px', threshold: 0 })
    observer.current = current
    for (const card of cards.current.keys()) current.observe(card)
    return () => { current.disconnect(); observer.current = null }
  }, [])

  const observeCard = useCallback((node: HTMLElement, id: number) => {
    cards.current.set(node, id)
    observer.current?.observe(node)
    return () => {
      observer.current?.unobserve(node)
      cards.current.delete(node)
      setVisible((previous) => {
        if (!previous.has(id)) return previous
        const next = new Set(previous)
        next.delete(id)
        return next
      })
    }
  }, [])

  return { visible, observeCard }
}
