import { Component } from 'react'

// App-wide error boundary. Without one, any render-time exception in any
// component unmounts the whole React tree and the user is left staring at a
// blank window with no way out. This catches it, shows a friendly recovery
// screen, and lets them reload the renderer (the backend keeps running).
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    // Surface in the terminal / electron-log for diagnosis from user reports.
    console.error('[Rudo] render error:', error, info?.componentStack)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div style={{
        position: 'fixed', inset: 0, display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center', gap: 16, padding: 32,
        background: '#0b0b14', color: '#e7e7ef', textAlign: 'center',
        fontFamily: 'system-ui, sans-serif',
      }}>
        <div style={{ fontSize: 40 }}>😵‍💫</div>
        <h2 style={{ margin: 0, fontSize: 18 }}>Something went wrong</h2>
        <p style={{ margin: 0, maxWidth: 420, opacity: 0.7, fontSize: 14, lineHeight: 1.5 }}>
          Rudo hit an unexpected error and couldn’t render this screen. Your files
          and index are untouched — reloading usually fixes it.
        </p>
        <button
          onClick={() => window.location.reload()}
          style={{
            marginTop: 8, padding: '10px 20px', borderRadius: 8, border: 'none',
            background: '#6c5cff', color: '#fff', fontSize: 14, cursor: 'pointer',
          }}
        >
          Reload Rudo
        </button>
      </div>
    )
  }
}
