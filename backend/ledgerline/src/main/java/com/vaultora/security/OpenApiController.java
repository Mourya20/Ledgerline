package com.vaultora.security;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenApiController {

    @GetMapping(value = "/v3/api-docs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getApiDocs() {
        return ResponseEntity.ok(buildOpenApiJson());
    }

    @GetMapping(value = {"/swagger-ui.html", "/api/v1/docs"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getSwaggerUi() {
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>Vaultora Banking System - API Documentation</title>
                  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css" />
                  <style>
                    html { box-sizing: border-box; overflow: -moz-scrollbars-vertical; overflow-y: scroll; }
                    *, *:before, *:after { box-sizing: inherit; }
                    body { margin:0; background: #fafafa; font-family: sans-serif; }
                    .topbar { background-color: #0D6E64 !important; }
                  </style>
                </head>
                <body>
                  <div id="swagger-ui"></div>
                  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-standalone-preset.js"></script>
                  <script>
                  window.onload = function() {
                    window.ui = SwaggerUIBundle({
                      url: "/v3/api-docs",
                      dom_id: '#swagger-ui',
                      deepLinking: true,
                      presets: [
                        SwaggerUIBundle.presets.apis,
                        SwaggerUIStandalonePreset
                      ],
                      plugins: [
                        SwaggerUIBundle.plugins.DownloadUrl
                      ],
                      layout: "StandaloneLayout"
                    });
                  };
                  </script>
                </body>
                </html>
                """;
        return ResponseEntity.ok(html);
    }

    private String buildOpenApiJson() {
        return """
        {
          "openapi": "3.1.0",
          "info": {
            "title": "Vaultora Banking System API",
            "version": "1.0.0",
            "description": "Production-grade core banking engine with double-entry ledger, maker-checker authorization, transaction limits, auditor dashboard, and customer account statements."
          },
          "servers": [
            {
              "url": "http://localhost:8080",
              "description": "Local Development Server"
            }
          ],
          "components": {
            "securitySchemes": {
              "cookieAuth": {
                "type": "apiKey",
                "in": "cookie",
                "name": "JSESSIONID",
                "description": "Spring Security Session Cookie"
              },
              "csrfToken": {
                "type": "apiKey",
                "in": "header",
                "name": "X-XSRF-TOKEN",
                "description": "Spring Security Double-Submit CSRF Token"
              }
            }
          },
          "paths": {
            "/api/v1/auth/csrf": {
              "get": {
                "summary": "Obtain CSRF Token",
                "tags": ["Authentication"],
                "responses": { "200": { "description": "CSRF token returned" } }
              }
            },
            "/api/v1/auth/register": {
              "post": {
                "summary": "Customer Registration",
                "tags": ["Authentication"],
                "description": "Registers a new customer account with email_verified = false and initiates verification token.",
                "responses": { "201": { "description": "Customer registered" }, "409": { "description": "Duplicate email" } }
              }
            },
            "/api/v1/auth/login": {
              "post": {
                "summary": "User Login",
                "tags": ["Authentication"],
                "description": "Authenticates user session. Unverified customers receive 403.",
                "responses": { "200": { "description": "Login successful" }, "401": { "description": "Invalid credentials" }, "403": { "description": "Email not verified" } }
              }
            },
            "/api/v1/auth/verify-email": {
              "post": {
                "summary": "Verify Customer Email",
                "tags": ["Authentication"],
                "responses": { "200": { "description": "Email verified" }, "400": { "description": "Invalid or expired token" } }
              }
            },
            "/api/v1/auth/resend-verification": {
              "post": {
                "summary": "Resend Customer Verification Email",
                "tags": ["Authentication"],
                "responses": { "200": { "description": "Verification link resent" } }
              }
            },
            "/api/v1/auth/forgot-password": {
              "post": {
                "summary": "Initiate Password Recovery",
                "tags": ["Authentication"],
                "responses": { "200": { "description": "Generic non-enumerating confirmation response" } }
              }
            },
            "/api/v1/auth/reset-password": {
              "post": {
                "summary": "Redeem Password Reset Token",
                "tags": ["Authentication"],
                "responses": { "200": { "description": "Password successfully updated" }, "400": { "description": "Invalid or expired token" } }
              }
            },
            "/api/v1/auth/password": {
              "post": {
                "summary": "Change Current Password (Authenticated)",
                "tags": ["Authentication"],
                "responses": { "200": { "description": "Password updated" }, "400": { "description": "Current password mismatch" } }
              }
            },
            "/api/v1/accounts": {
              "get": {
                "summary": "List User Accounts",
                "tags": ["Accounts"],
                "responses": { "200": { "description": "User accounts list" } }
              },
              "post": {
                "summary": "Direct Account Creation (Admin Only)",
                "tags": ["Accounts"],
                "responses": { "201": { "description": "Account created" }, "403": { "description": "Customer bypass forbidden" } }
              }
            },
            "/api/v1/accounts/{id}/statement": {
              "get": {
                "summary": "Download Account Statement (PDF / CSV)",
                "tags": ["Accounts"],
                "parameters": [
                  { "name": "id", "in": "path", "required": true },
                  { "name": "format", "in": "query", "description": "pdf or csv (default pdf)" },
                  { "name": "startDate", "in": "query" },
                  { "name": "endDate", "in": "query" }
                ],
                "responses": { "200": { "description": "Binary statement attachment" }, "403": { "description": "Ownership denied" } }
              }
            },
            "/api/v1/account-requests": {
              "get": {
                "summary": "List Account Opening Requests",
                "tags": ["Account Requests"],
                "responses": { "200": { "description": "Requests list" } }
              },
              "post": {
                "summary": "Submit Account Opening Request",
                "tags": ["Account Requests"],
                "responses": { "201": { "description": "Request created" } }
              }
            },
            "/api/v1/account-requests/{id}/approve": {
              "post": {
                "summary": "Approve Account Opening Request (Admin Only)",
                "tags": ["Account Requests"],
                "responses": { "200": { "description": "Request approved and account generated" } }
              }
            },
            "/api/v1/account-requests/{id}/reject": {
              "post": {
                "summary": "Reject Account Opening Request (Admin Only)",
                "tags": ["Account Requests"],
                "responses": { "200": { "description": "Request rejected" } }
              }
            },
            "/api/v1/transactions/deposit": {
              "post": {
                "summary": "Deposit Funds",
                "tags": ["Transactions"],
                "responses": { "201": { "description": "Deposit completed" } }
              }
            },
            "/api/v1/transactions/withdraw": {
              "post": {
                "summary": "Withdraw Funds",
                "tags": ["Transactions"],
                "description": "Enforces transaction limits and account balance.",
                "responses": { "201": { "description": "Withdrawal completed" }, "400": { "description": "Limit exceeded" }, "422": { "description": "Insufficient funds" } }
              }
            },
            "/api/v1/transactions/transfer": {
              "post": {
                "summary": "Transfer Funds",
                "tags": ["Transactions"],
                "description": "Enforces transaction limits, double-entry ledger, and balance check.",
                "responses": { "201": { "description": "Transfer completed" }, "400": { "description": "Limit exceeded" }, "422": { "description": "Insufficient funds" } }
              }
            },
            "/api/v1/audit/transactions": {
              "get": {
                "summary": "Auditor Transaction Inspection",
                "tags": ["Auditor"],
                "description": "Filtered search with pagination and sorting for auditor portal.",
                "responses": { "200": { "description": "Transactions page" }, "403": { "description": "Customer access denied" } }
              }
            },
            "/api/v1/audit/transactions/export": {
              "get": {
                "summary": "Auditor Transactions CSV Export",
                "tags": ["Auditor"],
                "description": "RFC 4180 CSV export with spreadsheet formula injection defense.",
                "responses": { "200": { "description": "CSV file download" } }
              }
            },
            "/api/v1/audit/ledger-integrity": {
              "get": {
                "summary": "Auditor Ledger Integrity Verification",
                "tags": ["Auditor"],
                "description": "Read-only global debit-credit equality check and account balance reconciliation.",
                "responses": { "200": { "description": "Ledger integrity report" } }
              }
            },
            "/api/v1/admin/settings/limits": {
              "get": {
                "summary": "Get Transaction Limits (Admin Only)",
                "tags": ["Admin Settings"],
                "responses": { "200": { "description": "Current limits" } }
              },
              "put": {
                "summary": "Update Transaction Limits (Admin Only)",
                "tags": ["Admin Settings"],
                "responses": { "200": { "description": "Updated limits" } }
              }
            },
            "/api/v1/admin/maker-checker/requests": {
              "get": {
                "summary": "List Maker-Checker Approval Requests (Admin Only)",
                "tags": ["Maker-Checker"],
                "responses": { "200": { "description": "Approval requests list" } }
              }
            },
            "/api/v1/admin/maker-checker/requests/{id}/approve": {
              "post": {
                "summary": "Approve Maker-Checker Request (Checker Admin Only)",
                "tags": ["Maker-Checker"],
                "description": "Enforces maker-checker separation: initiator cannot approve their own request.",
                "responses": { "200": { "description": "Request approved and executed atomically" }, "400": { "description": "Self-approval rejected" } }
              }
            },
            "/api/v1/admin/maker-checker/requests/{id}/reject": {
              "post": {
                "summary": "Reject Maker-Checker Request (Checker Admin Only)",
                "tags": ["Maker-Checker"],
                "responses": { "200": { "description": "Request rejected" } }
              }
            },
            "/api/v1/admin/transactions/{id}/reverse": {
              "post": {
                "summary": "Initiate Transaction Reversal (Admin Only)",
                "tags": ["Maker-Checker"],
                "description": "Creates pending maker-checker reversal request without posting ledger entries.",
                "responses": { "201": { "description": "Reversal initiated" } }
              }
            }
          }
        }
        """;
    }
}
