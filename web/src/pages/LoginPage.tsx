import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import styles from './AuthForm.module.css';

export function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return 'Incorrect email or password.';
    }
    if (error.status === 403) {
      return error.detail ?? 'This account is locked.';
    }
    if (error.status === 409) {
      return error.detail ?? 'That email is already registered.';
    }
    if (error.status === 400) {
      return error.detail ?? 'Please check the form and try again.';
    }
  }
  return 'Something went wrong. Please try again.';
}

export function LoginPage() {
  const { status, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === 'authenticated' && !submitting) {
    return <Navigate to={from} replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email.trim(), password);
      navigate(from, { replace: true });
    } catch (e) {
      setError(messageFor(e));
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <p className={styles.brand}>Tradewind</p>
        <h1 className={styles.title}>Log in</h1>
        <form className={styles.form} onSubmit={handleSubmit} noValidate>
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          <label className={styles.field}>
            Email
            <input className={styles.input} type="email" autoComplete="email" required value={email}
                   onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label className={styles.field}>
            Password
            <input className={styles.input} type="password" autoComplete="current-password" required value={password}
                   onChange={(e) => setPassword(e.target.value)} />
          </label>
          <button className={styles.submit} type="submit" disabled={submitting || !email || !password}>
            {submitting ? 'Logging in…' : 'Log in'}
          </button>
        </form>
        <p className={styles.switch}>
          New to Tradewind? <Link to="/register">Create an account</Link>
        </p>
      </div>
    </div>
  );
}
