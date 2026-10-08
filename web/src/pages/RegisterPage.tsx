import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { messageFor } from './LoginPage';
import styles from './AuthForm.module.css';

// Same limits as account-service: BCrypt only uses the first 72 bytes.
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 72;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function validate(email: string, password: string): string | null {
  if (!EMAIL.test(email.trim())) {
    return 'Enter a valid email address.';
  }
  const bytes = new TextEncoder().encode(password).length;
  if (password.length < PASSWORD_MIN || bytes > PASSWORD_MAX) {
    return `Password must be ${PASSWORD_MIN}–${PASSWORD_MAX} characters.`;
  }
  return null;
}

export function RegisterPage() {
  const { status, register } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === 'authenticated' && !submitting) {
    return <Navigate to="/" replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const invalid = validate(email, password);
    if (invalid) {
      setError(invalid);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await register(email.trim(), password);
      navigate('/', { replace: true });
    } catch (e) {
      setError(messageFor(e));
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <p className={styles.brand}>Tradewind</p>
        <h1 className={styles.title}>Create your account</h1>
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
            <input className={styles.input} type="password" autoComplete="new-password" required value={password}
                   onChange={(e) => setPassword(e.target.value)} />
            <span className={styles.hint}>
              {PASSWORD_MIN}–{PASSWORD_MAX} characters
            </span>
          </label>
          <button className={styles.submit} type="submit" disabled={submitting}>
            {submitting ? 'Creating account…' : 'Create account'}
          </button>
        </form>
        <p className={styles.switch}>
          Already have an account? <Link to="/login">Log in</Link>
        </p>
      </div>
    </div>
  );
}
