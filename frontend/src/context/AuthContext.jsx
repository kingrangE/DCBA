import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.me()
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setLoading(false))
  }, [])

  const value = useMemo(() => ({
    user,
    loading,
    login: async (credentials) => {
      const loggedInUser = await api.login(credentials)
      setUser(loggedInUser)
    },
    signup: api.signup,
    logout: async () => {
      await api.logout()
      setUser(null)
    },
    updateSlack: async (slackId) => {
      const updatedUser = await api.updateSlack(slackId)
      setUser(updatedUser)
      return updatedUser
    },
  }), [loading, user])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
