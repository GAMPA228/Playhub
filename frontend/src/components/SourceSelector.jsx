import React, { useId, useLayoutEffect, useRef, useState } from 'react'

export default function SourceSelector({ sources, selectedSourceUid, onSelect, disabled = false }) {
  const listId = useId()
  const headingId = useId()
  const viewportRef = useRef(null)
  const gridRef = useRef(null)
  const [expanded, setExpanded] = useState(false)
  const [visibleCount, setVisibleCount] = useState(sources.length)

  useLayoutEffect(() => {
    const grid = gridRef.current
    if (!grid) return

    const measure = () => {
      const buttons = Array.from(grid.children)
      const firstTop = buttons[0]?.offsetTop ?? 0
      let secondTop = null
      const count = buttons.filter((button) => {
        if (button.offsetTop === firstTop) return true
        if (secondTop === null) secondTop = button.offsetTop
        return button.offsetTop === secondTop
      }).length
      setVisibleCount(count)
    }

    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(grid)
    return () => observer.disconnect()
  }, [sources])

  if (!sources.length) return null

  const selectedSource = sources.find((source) => source.uid === selectedSourceUid) || sources[0]
  const selectedName = selectedSource.name || selectedSource.key || selectedSource.uid
  const hasMore = sources.length > visibleCount

  const toggleExpanded = () => {
    if (expanded && viewportRef.current) viewportRef.current.scrollTop = 0
    setExpanded((value) => !value)
  }

  return (
    <section className="source-selector" aria-labelledby={headingId}>
      <div className="source-selector__header">
        <h3 className="source-selector__heading" id={headingId}>视频源（{sources.length}）</h3>
        <div className="source-selector__current">
          <span className="source-selector__current-name" title={selectedName}>当前：{selectedName}</span>
          {disabled && <span className="source-selector__busy" role="status">处理中…</span>}
        </div>
        {hasMore && (
          <button
            type="button"
            className="source-selector__toggle"
            aria-expanded={expanded}
            aria-controls={listId}
            onClick={toggleExpanded}
          >
            {expanded ? '收起' : '展开全部'} <span aria-hidden="true">{expanded ? '↑' : '↓'}</span>
          </button>
        )}
      </div>
      <div
        ref={viewportRef}
        className={`source-selector__viewport${expanded ? ' source-selector__viewport--expanded' : ''}`}
        id={listId}
        aria-busy={disabled}
      >
        <div className="source-selector__grid" ref={gridRef}>
          {sources.map((source, index) => {
            const name = source.name || source.key || source.uid
            const selected = source.uid === selectedSource.uid
            const hidden = !expanded && index >= visibleCount
            return (
              <button
                key={source.uid}
                type="button"
                className={`source-selector__source${selected ? ' source-selector__source--selected' : ''}`}
                title={name}
                aria-pressed={selected}
                aria-hidden={hidden || undefined}
                tabIndex={hidden ? -1 : 0}
                style={hidden ? { visibility: 'hidden' } : undefined}
                disabled={disabled}
                onClick={() => { if (!selected) onSelect(source.uid) }}
              >
                {name}
              </button>
            )
          })}
        </div>
      </div>
    </section>
  )
}
