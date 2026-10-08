import { NavLink, Outlet, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { ConnectionBadge } from './ConnectionBadge';
import { SymbolSearch } from './SymbolSearch';
import styles from './AppLayout.module.css';

const linkClass = ({ isActive }: { isActive: boolean }) => (isActive ? `${styles.link} ${styles.active}` : styles.link);

export function AppLayout() {
  const { logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate('/login', { replace: true });
  }

  return (
    <>
      <header className={styles.header}>
        <div className={styles.bar}>
          <NavLink to="/" className={styles.brand}>
            Tradewind
          </NavLink>
          <nav className={styles.nav} aria-label="Main">
            <NavLink to="/" end className={linkClass}>
              Home
            </NavLink>
            <NavLink to="/orders" className={linkClass}>
              Orders
            </NavLink>
          </nav>
          <SymbolSearch />
          <ConnectionBadge />
          <button type="button" className={styles.logout} onClick={handleLogout}>
            Log out
          </button>
        </div>
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
    </>
  );
}
