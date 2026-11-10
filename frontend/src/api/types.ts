export interface UserResponse {
  userId: string;
  email: string;
  role: string;
}

export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';
export type AccountType = 'CUSTOMER' | 'SYSTEM';

export interface AccountResponse {
  id: string;
  accountNumber: string;
  accountType: AccountType;
  currency: string;
  balance: number;
  status: AccountStatus;
  createdAt: string;
  ownerEmail?: string | null;
  ownerId?: string | null;
}

export interface AccountBalanceResponse {
  accountId: string;
  accountNumber: string;
  currency: string;
  balance: number;
}

export type EntryType = 'DEBIT' | 'CREDIT';
export type TransactionStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REVERSED';

export interface TransactionEntryResponse {
  id: string;
  accountId: string | null;
  accountNumber: string;
  entryType: EntryType;
  amount: number;
  currency: string;
}

export interface TransactionResponse {
  id: string;
  transactionType: string;
  status: TransactionStatus;
  currency: string;
  entries: TransactionEntryResponse[];
  createdAt: string;
  completedAt: string | null;
  relatedTransactionId?: string | null;
  reversalReason?: string | null;
}

export interface Page<T> {
  content: T[];
  pageable: unknown;
  totalElements: number;
  totalPages: number;
  last: boolean;
  first: boolean;
  size: number;
  number: number;
  numberOfElements: number;
  empty: boolean;
}

export interface DepositRequest {
  accountId: string;
  amount: number;
  currency: string;
}

export interface WithdrawRequest {
  accountId: string;
  amount: number;
  currency: string;
}

export interface TransferRequest {
  sourceAccountId: string;
  destinationAccountNumber: string;
  amount: number;
  currency: string;
}

export type AccountRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface AccountRequest {
  id: string;
  userId: string;
  userEmail: string;
  currency: string;
  status: AccountRequestStatus;
  adminNotes?: string | null;
  reviewedBy?: string | null;
  accountId?: string | null;
  accountNumber?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAccountOpeningRequest {
  currency: string;
}

export interface ReviewAccountRequest {
  adminNotes?: string;
}

export interface CreateAccountDirectRequest {
  customerId?: string;
  customerEmail?: string;
  customerPassword?: string;
  role?: string;
  currency?: string;
}

export type CreateAccountRequest = CreateAccountDirectRequest;

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

export interface VerifyEmailRequest {
  token: string;
}

export interface ResendVerificationRequest {
  email: string;
}

export interface CsrfResponse {
  token: string;
}

export interface ApiError {
  status?: number;
  error?: string;
  message?: string;
  timestamp?: string;
}

export interface AuditLogItem {
  id: string;
  actorUserId: string | null;
  action: string;
  resourceType: string | null;
  resourceId: string | null;
  details: Record<string, unknown>;
  createdAt: string;
}

export interface HealthResponse {
  status: string;
  groups?: string[];
  components?: Record<string, { status: string; details?: Record<string, unknown> }>;
}

export type UserStatus = 'ACTIVE' | 'DISABLED';

export interface UserSummaryResponse {
  id: string;
  email: string;
  role: string;
  status: UserStatus;
  createdAt: string;
  accountCount: number;
}

export interface AdminStatsResponse {
  totalUsers: number;
  totalCustomers: number;
  totalAccounts: number;
  totalCustomerBalance: number;
  totalTransactions: number;
}

export interface UpdateUserStatusRequest {
  status: UserStatus;
}

export interface UpdateAccountStatusRequest {
  status: AccountStatus;
}

export type MakerCheckerType = 'LARGE_TRANSFER' | 'TRANSACTION_REVERSAL';
export type MakerCheckerStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface MakerCheckerResponse {
  id: string;
  requestType: MakerCheckerType;
  status: MakerCheckerStatus;
  initiatedByEmail: string;
  reviewedByEmail?: string | null;
  reviewedAt?: string | null;
  rejectionReason?: string | null;
  amount?: number | null;
  currency: string;
  sourceAccountNumber?: string | null;
  destinationAccountNumber?: string | null;
  originalTransactionId?: string | null;
  resultingTransactionId?: string | null;
  description?: string | null;
  createdAt: string;
}

export interface AdminTransferRequest {
  sourceAccountId: string;
  destinationAccountNumber: string;
  amount: number;
  description?: string;
}

export interface TransactionLimitResponse {
  id: string;
  currency: string;
  maxPerTransaction: number;
  maxDailyTotal: number;
  updatedAt: string;
  updatedByEmail?: string | null;
}

export interface UpdateTransactionLimitRequest {
  maxPerTransaction: number;
  maxDailyTotal: number;
}

export interface AuditorTransactionResponse {
  id: string;
  transactionType: string;
  status: TransactionStatus;
  currency: string;
  initiatedByEmail: string;
  relatedTransactionId?: string | null;
  reversalReason?: string | null;
  createdAt: string;
  completedAt?: string | null;
  amount: number;
  sourceAccount: string;
  destinationAccount: string;
}

export interface LedgerIntegrityResponse {
  status: 'INTACT' | 'COMPROMISED';
  verifiedAt: string;
  transactionsChecked: number;
  accountsChecked: number;
  totalDebits: number;
  totalCredits: number;
  debitCreditEqual: boolean;
  discrepanciesCount: number;
  discrepancies: string[];
}
