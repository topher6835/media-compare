import { useEffect, useState } from 'react'
import { Route, Routes } from 'react-router-dom'

function Home() {
  const [status, setStatus] = useState('checking...')

  useEffect(() => {
    fetch('/api/health')
      .then((response) => response.text())
      .then(setStatus)
      .catch(() => setStatus('error'))
  }, [])

  return (
    <>
      <h1>Media Compare</h1>
      <p>Backend: {status}</p>
    </>
  )
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
    </Routes>
  )
}

export default App