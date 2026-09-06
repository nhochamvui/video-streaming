import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useAppDispatch, useAppSelector } from '../../store/hooks'
import { loadActiveStreams } from '../../store/streamsSlice'

export function ActiveStreamsList() {
  const dispatch = useAppDispatch()
  const { names, thumbnails, namesStatus } = useAppSelector((state) => state.streams)

  useEffect(() => {
    void dispatch(loadActiveStreams())
  }, [dispatch])

  if (namesStatus === 'loading' && names.length === 0) {
    return <p className="loading">Loading streams...</p>
  }

  if (names.length === 0) {
    return <p className="stream-list__empty">No active streams.</p>
  }

  return (
    <div className="stream-grid">
      {names.map((name) => (
        <Link key={name} to={`/${encodeURIComponent(name)}`} className="stream-card">
          <div className="stream-card__thumb">
            {thumbnails[name] ? (
              <img
                src={thumbnails[name]}
                alt={`Stream ${name}`}
                loading="lazy"
                onError={(e) => {
                  e.currentTarget.style.display = 'none'
                }}
              />
            ) : null}
            <span className="stream-card__placeholder">No Preview</span>
          </div>
          <div className="stream-card__label">{name}</div>
        </Link>
      ))}
    </div>
  )
}
