# Vaultora — Frontend Application

Clean, responsive banking frontend for Vaultora implemented using React 19, Vite 8, TypeScript 7, and React Router 7 based on the five-page visual design specification (`Ledgerline – Frontend Design.pdf`).

---

## 1. Prerequisites

- **Node.js**: `v20.x` or `v22.x` (LTS recommended, minimum `v18.0.0+`)
- **npm**: `v10.x+` (bundled with Node.js)
- **Backend Service**: Vaultora Spring Boot backend running on `http://localhost:8080` (see root `README.md`)

---

## 2. Setup & Installation

From the `frontend/` directory:

```bash
# Recommended reproducible installation
npm ci

# Alternatively
npm install
```

### Environment Variables
Configure `.env` in `frontend/` (see `.env.example`):

```env
# Leave empty to utilize the Vite development proxy (/api -> http://localhost:8080)
# Or define an explicit backend origin if running across separate origins:
VITE_API_BASE_URL=
```

---

## 3. Development & Production Scripts

```bash
# Run local Vite development server with HMR on http://localhost:5173
npm run dev

# Run Vitest test suite (unit, formatting, validation, and component tests)
npm test

# Type-check and build production distribution into dist/
npm run build

# Preview production build locally
npm run preview
```

---

## 4. Architecture Overview

The frontend application follows a clean modular architecture:

```
frontend/
├── src/
│   ├── api/
│   │   ├── client.ts          # Axios-like custom Fetch wrapper, CSRF handling, error mapping
│   │   └── types.ts           # Strict TypeScript DTO contracts matching backend API
│   ├── components/
│   │   └── layout/
│   │       ├── AppLayout.tsx  # Responsive shell: desktop sidebar + mobile header & toggle drawer
│   │       └── ProtectedRoute.tsx # Route guard redirecting unauthenticated users to /login
│   ├── context/
│   │   └── AuthContext.tsx    # Session-backed auth state (user, login, logout, register, refresh)
│   ├── pages/
│   │   ├── LoginPage.tsx      # Customer authentication screen (PDF Page 1)
│   │   ├── RegisterPage.tsx   # Customer registration with 12-char / 72-byte UTF-8 validation
│   │   ├── DashboardPage.tsx  # Overview with total balance, accounts list, quick actions (PDF Page 2)
│   │   ├── AccountsPage.tsx   # Honest account details, masked account numbers, new account modal (PDF Page 3)
│   │   ├── MoveMoneyPage.tsx  # Deposit, Withdraw, and Transfer with Idempotency Lifecycle (PDF Page 4)
│   │   └── HistoryPage.tsx    # Transaction history, account filter, own-account transfer view (PDF Page 5)
│   ├── utils/
│   │   ├── formatters.ts      # Currency, date, masked numbers, customer-centric entry parsing
│   │   ├── idempotencyManager.ts # Transaction intent lifecycle & sessionStorage persistence
│   │   └── validation.ts      # Registration password validation (min 12 chars, max 72 UTF-8 bytes)
│   ├── App.tsx                # Route definitions & AuthProvider integration
│   ├── main.tsx               # Application entrypoint
│   └── index.css              # Custom responsive stylesheet preserving PDF visual language
├── vite.config.ts             # Vite configuration with /api proxy to localhost:8080 & Vitest config
└── package.json
```

---

## 5. Key Conventions & Integration Highlights

1. **Authentication & Session Persistence**:
   - Uses Spring Security cookie-based session management (`JSESSIONID`) with `credentials: "include"`.
   - Never stores passwords, tokens, or session IDs in `localStorage` or `sessionStorage`.
   - Auth state is refreshed on boot via `GET /api/v1/auth/me`.

2. **CSRF Protection**:
   - Double-submit cookie pattern with `XSRF-TOKEN` cookie and `GET /api/v1/auth/csrf`.
   - Mutating requests (`POST`, `PUT`, `DELETE`) automatically extract and attach the `X-XSRF-TOKEN` header.

3. **Transaction Idempotency Lifecycle**:
   - All financial operations (`deposit`, `withdraw`, `transfer`) use unique UUID keys in the `Idempotency-Key` request header.
   - Uncertain outcomes (network drop, timeout, 5xx error) preserve the exact intent and payload in `sessionStorage` (`vaultora_tx_intent`).
   - Reloading the page displays an unresolved intent banner with options to "Retry Pending Transaction" or "Discard Intent".
   - The user cannot submit an altered payload while an intent is pending.
   - Retries reuse the exact original key, letting backend PostgreSQL advisory locks and SHA-256 fingerprinting safely return the existing transaction.
   - Successful `201 Created` responses clear the intent immediately; subsequent operations generate fresh UUID keys.

4. **Honest Domain Presentation**:
   - Accounts are identified by actual masked account numbers (`Account ••••4821`) and real trigger-projected balances.
   - Fabricated full names, non-existent checking/savings product types, and fake save buttons have been eliminated.
   - User profile displays real `email`, real `userId`, and `role`.
   - Own-account transfers correctly display both sender (debit) and receiver (credit) legs based on the viewed account.

5. **Visual Styling & Responsive Navigation**:
   - Faithful adherence to the design PDF: teal brand accent (`#0f766e`), soft background (`#f8fafc`), clean white cards, semantic status pills.
   - Desktop: Persistent left sidebar navigation with active route highlights.
   - Mobile: Dedicated top header with accessible hamburger drawer button (`aria-expanded`, keyboard navigable), sliding drawer, and auto-closing links.
