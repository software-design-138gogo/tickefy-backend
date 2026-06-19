workspace "Tickefy - C4 Level 3 (Hiep: auth / inventory / order)" "Component views for the three backend services owned by Hiep. Spring layered (no hexagonal use-case/port-adapter layer). Dev/stub components are tagged." {

    !identifiers hierarchical

    model {
        // =============================================================
        // PEOPLE (chỉ vai trò chạm 3 service này)
        // =============================================================
        audience  = person "Audience"  "Buys tickets, views orders."
        organizer = person "Organizer" "Configures ticket types."

        tickefy = softwareSystem "Tickefy" "Concert ticketing and check-in platform." {
            tags "InternalSystem"

            // ---- Neighbour containers (chỉ là hộp, không mở component) ----
            apiGateway = container "API Gateway" "Edge routing + JWT verify + rate limit." "Spring Cloud Gateway" {
                tags "API"
            }
            postgres = container "PostgreSQL (schema-per-service)" "auth_service / inventory_service / order_service schemas." "PostgreSQL" {
                tags "Database"
            }
            redis = container "Redis" "Blacklist (auth) + atomic stock/limit Lua (inventory)." "Redis" {
                tags "Cache"
            }
            rabbitMq = container "Message Broker" "tickefy.exchange — domain events." "RabbitMQ" {
                tags "MessageBroker"
            }

            // =========================================================
            // CONTAINER IN SCOPE 1 — AUTH SERVICE
            // =========================================================
            authService = container "Auth Service" "Users, authentication, JWT, RBAC." "Spring Boot" {

                group "Inbound" {
                    authApi  = component "AuthController" "register / login / refresh-token / logout; set/clear cookie." "REST /auth" "InboundAdapter"
                    userApi  = component "UserController" "/me; ADMIN role mgmt + list users." "REST /auth" "InboundAdapter"
                    authJwtFilter = component "JwtAuthenticationFilter" "Reads Bearer (not cookie), verify + blacklist check." "Security Filter" "InboundAdapter"
                    adminBootstrap = component "AdminBootstrapRunner" "Boot-time idempotent ADMIN seed (env-gated)." "ApplicationRunner" "BackgroundJob"
                }

                group "Services" {
                    authSvc    = component "AuthService" "register/login/refresh/logout logic; anti-enum dummy-hash." "Spring Service" "ApplicationComponent"
                    userSvc    = component "UserService" "role assign/revoke (LAST_ADMIN guard), list." "Spring Service" "ApplicationComponent"
                    refreshSvc = component "RefreshTokenService" "Opaque refresh: create (SHA-256 store), verify, revokeAll." "Spring Service" "ApplicationComponent"
                }

                group "Outbound / Security" {
                    jwtProvider  = component "JwtTokenProvider" "Issue/parse RS256 access; gen refresh raw+hash." "JWT (RS256)" "OutboundAdapter"
                    jwtBlacklist = component "JwtBlacklistService" "jti blacklist in Redis; fail-safe on Redis down." "Redis Adapter" "OutboundAdapter"
                    userRepo     = component "UserRepository" "users table." "Spring Data JPA" "Repository"
                    roleRepo     = component "RoleRepository" "roles / user_roles." "Spring Data JPA" "Repository"
                    refreshRepo  = component "RefreshTokenRepository" "refresh_tokens." "Spring Data JPA" "Repository"
                }
            }

            // =========================================================
            // CONTAINER IN SCOPE 2 — INVENTORY SERVICE
            // =========================================================
            inventoryService = container "Inventory Service" "Ticket stock, reservations, per-user limits, anti-over-sell." "Spring Boot" {

                group "Inbound" {
                    reserveApi    = component "ReservationController" "POST reserve (authenticated)." "REST /api/inventory/reservations" "InboundAdapter"
                    ticketTypeApi = component "TicketTypeController" "create (ORGANIZER) / list / availability (public)." "REST /api/inventory/concerts/*/ticket-types" "InboundAdapter"
                    limitApi      = component "PurchaseLimitController" "GET per-user purchase limit." "REST /api/inventory/users" "InboundAdapter"
                    orderPaidConsumer    = component "OrderPaidConsumer" "rk order.paid -> commit reservation." "RabbitMQ Consumer" "InboundAdapter"
                    orderReleaseConsumer = component "OrderReleaseConsumer" "rk order.payment.failed / order.expired -> release." "RabbitMQ Consumer" "InboundAdapter"
                    devSeed = component "DevSeedRunner" "Dev-only seed: 1 concert + 5 ticket types (env-gated)." "ApplicationRunner" "DevStub"
                }

                group "Services" {
                    reserveSvc   = component "ReservationService" "Reserve orchestrator: idempotent lookup -> Lua atomic -> DB; Redis compensation/fallback." "Spring Service" "ApplicationComponent"
                    lifecycleSvc = component "ReservationLifecycleService" "commit / release (status-guard idempotent)." "Spring Service (TX)" "ApplicationComponent"
                    ticketTypeSvc = component "TicketTypeService" "create ticket type (+inventory row +Redis seed), availability." "Spring Service" "ApplicationComponent"
                    limitSvc      = component "PurchaseLimitService" "remaining per-user limit (read-only)." "Spring Service" "ApplicationComponent"
                    reservePersistence = component "ReservationPersistence" "Short-TX DB writes (anti self-invocation bean)." "Spring Service (TX)" "ApplicationComponent"
                }

                group "Outbound" {
                    redisLua  = component "InventoryRedisService" "Lua atomic over-sell + per-user-limit; stock/meta keys." "Redis / Lua" "OutboundAdapter"
                    ticketTypeRepo = component "TicketTypeRepository" "ticket_types." "Spring Data JPA" "Repository"
                    inventoryRepo  = component "TicketTypeInventoryRepository" "ticket_type_inventory (commit/release reserved)." "Spring Data JPA" "Repository"
                    reservationRepo = component "TicketReservationRepository" "ticket_reservations (idempotent lookup)." "Spring Data JPA" "Repository"
                }
            }

            // =========================================================
            // CONTAINER IN SCOPE 3 — ORDER SERVICE
            // =========================================================
            orderService = container "Order Service" "Purchase saga orchestration + order lifecycle (no Redis)." "Spring Boot" {

                group "Inbound" {
                    orderApi = component "OrderController" "POST /orders (create/resume), GET order(s)." "REST /orders" "InboundAdapter"
                    paymentConsumer = component "PaymentEventConsumer" "rk payment.succeeded/failed -> markPaid/markPaymentFailed." "RabbitMQ Consumer" "InboundAdapter"
                    expireWorker = component "OrderExpireWorker" "@Scheduled: expire PAYMENT_PENDING past deadline." "Scheduled Job" "BackgroundJob"
                    devPayApi = component "DevPaymentController" "Dev-only: simulate-paid/failed, publishes payment.* (env-gated)." "REST /dev (dev only)" "DevStub"
                }

                group "Services / Domain" {
                    orderSvc = component "OrderService" "Non-TX saga orchestrator (Cách B): reserve+pay outside TX, resume-by-status." "Spring Service" "ApplicationComponent"
                    orderPersistence = component "OrderPersistence" "Short-TX state transitions; writes outbox in same TX." "Spring Service (TX)" "ApplicationComponent"
                    stateMachine = component "OrderStateMachine" "assertTransition + allowed map (CONFLICT if illegal)." "Domain rule" "DomainComponent"
                }

                group "Outbound" {
                    inventoryClient = component "InventoryClient" "Direct HTTP reserve (forward Bearer, NOT via gateway)." "RestClient" "OutboundAdapter"
                    stubPaymentClient = component "StubPaymentClient" "Stub: fabricates txId, NO network (no real PaymentClient)." "Stub (no network)" "DevStub"
                    outboxPublisher = component "OutboxPublisher" "@Scheduled drain outbox -> publish order.paid/payment.failed/expired." "Outbox Publisher" "OutboundAdapter"
                    orderRepo = component "OrderRepository" "orders." "Spring Data JPA" "Repository"
                    orderItemRepo = component "OrderItemRepository" "order_items." "Spring Data JPA" "Repository"
                    outboxRepo = component "OutboxRepository" "outbox (payload jsonb)." "Spring Data JPA" "Repository"
                }
            }

            // =========================================================
            // FRONTEND WEB (Hiep = Web FE Lead). Mobile = Hoa -> placeholder, ngoài file này.
            // =========================================================
            sharedPkg = container "@tickefy/shared" "Shared TS: envelope/ErrorCode, api-client base (Bearer + refresh-on-401), gateway interfaces + selectGateway, auth model." "TypeScript (tsup ESM)" {
                tags "SharedLib"
            }

            customerWeb = container "Customer Web" "Audience web: browse, booking, checkout, e-tickets." "Next.js 16 App Router" {
                group "Routes" {
                    webRouter = component "App Router + layout" "Page shells (SSG/RSC/CSR) delegate to feature Views; providers (Query + AuthBootstrap)." "Next App Router" "Route"
                    webGuard  = component "RequireAuth" "Status-based guard (unknown/guest/authenticated) + returnUrl." "Route Guard" "Route"
                }
                group "Feature modules" {
                    webAuth     = component "Auth feature" "Login/register/logout + AuthBootstrap session." "Feature module" "FeatureModule"
                    webConcerts = component "Concerts feature" "Browse + detail (SSG) + ticket-types/availability." "Feature module" "FeatureModule"
                    webBooking  = component "Booking feature" "Zone/ticket select -> checkout orchestration." "Feature module" "FeatureModule"
                    webOrders   = component "Orders feature" "Create order (idempotency) + my-orders + payment poll." "Feature module" "FeatureModule"
                    webTickets  = component "Tickets feature" "My-tickets list + QR detail." "Feature module" "FeatureModule"
                    webSeatMap  = component "Seat-map feature" "SVG zones overlay + selection." "Feature module" "FeatureModule"
                }
                group "State" {
                    webAuthStore    = component "Auth store" "Zustand: user/roles/accessToken (memory only)." "Zustand" "Store"
                    webBookingStore = component "Booking store" "Zustand: selection + idempotencyKey (sessionStorage)." "Zustand" "Store"
                }
                group "Data" {
                    webApiClient = component "API client" "Axios: inject Bearer, 401 -> refresh single-flight -> retry." "Axios" "ApiClient"
                    webGateways  = component "Data gateways" "Mock<->real per env: auth/inventory/order REAL, concert/ticket/seatmap/payment MOCK." "selectGateway" "DataGateway"
                }
            }

            adminWeb = container "Admin Web" "Organizer/Admin web: concert/ticket CRUD, dashboard, AI-bio, CSV." "Vite + React 19" {
                group "Routes" {
                    adminRouter = component "React Router + AdminLayout" "createBrowserRouter; sidebar/header shell + outlet." "React Router v6" "Route"
                    adminGuard  = component "ProtectedRoute" "ROLE-based guard (ADMIN/ORGANIZER)." "Route Guard" "Route"
                }
                group "Feature modules" {
                    adminAuth        = component "Auth feature" "Mock login + role check (real auth deferred 2.21b)." "Feature module" "DevStub"
                    adminConcerts    = component "Concerts feature" "CRUD + lifecycle (DRAFT/PUBLISHED/CANCELLED)." "Feature module" "FeatureModule"
                    adminTicketTypes = component "Ticket-types feature" "Per-concert ticket-type CRUD." "Feature module" "FeatureModule"
                    adminOrders      = component "Orders feature" "Read-only list/detail." "Feature module" "FeatureModule"
                    adminPayments    = component "Payments feature" "Read-only transactions." "Feature module" "FeatureModule"
                    adminDashboard   = component "Dashboard feature" "Revenue + tickets charts (recharts)." "Feature module" "FeatureModule"
                    adminAiBio       = component "AI Bio feature" "Upload PDF -> job poll -> save bio." "Feature module" "FeatureModule"
                    adminCsv         = component "CSV import feature" "Upload VIP CSV -> job poll -> report." "Feature module" "FeatureModule"
                }
                group "State" {
                    adminAuthStore  = component "Auth store" "Zustand: user/role (persist localStorage)." "Zustand" "Store"
                    adminShellStore = component "Shell store" "Zustand: sidebar collapse." "Zustand" "Store"
                }
                group "Data" {
                    adminApiClient = component "API client" "Axios: baseURL :8080/api (token plumbing deferred)." "Axios" "DevStub"
                    adminGateways  = component "Data gateways" "All 9 MOCK-first (selectGateway, VITE_USE_MOCK_* default true)." "selectGateway" "DevStub"
                }
            }
        }

        // =============================================================
        // ===== AUTH — relationships
        // =============================================================
        audience  -> tickefy.apiGateway "Login / refresh" "HTTPS"
        organizer -> tickefy.apiGateway "Login / manage" "HTTPS"
        tickefy.apiGateway -> tickefy.authService.authApi "Routes /api/auth/**" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.authService.userApi "Routes /api/auth/users, /me" "HTTP/JSON"

        tickefy.authService.authApi -> tickefy.authService.authSvc "register/login/refresh/logout"
        tickefy.authService.userApi -> tickefy.authService.userSvc "/me + role mgmt"
        tickefy.authService.authJwtFilter -> tickefy.authService.jwtProvider "parse + verify"
        tickefy.authService.authJwtFilter -> tickefy.authService.jwtBlacklist "check jti"
        tickefy.authService.authSvc -> tickefy.authService.refreshSvc "issue/verify/revoke refresh"
        tickefy.authService.authSvc -> tickefy.authService.jwtProvider "issue access"
        tickefy.authService.authSvc -> tickefy.authService.jwtBlacklist "blacklist on logout"
        tickefy.authService.authSvc -> tickefy.authService.userRepo "load user"
        tickefy.authService.authSvc -> tickefy.authService.roleRepo "default role"
        tickefy.authService.userSvc -> tickefy.authService.userRepo "users"
        tickefy.authService.userSvc -> tickefy.authService.roleRepo "roles"
        tickefy.authService.refreshSvc -> tickefy.authService.refreshRepo "store/verify hash"
        tickefy.authService.refreshSvc -> tickefy.authService.jwtProvider "hash refresh"
        tickefy.authService.adminBootstrap -> tickefy.authService.userRepo "seed admin"
        tickefy.authService.adminBootstrap -> tickefy.authService.roleRepo "seed roles"

        tickefy.authService.jwtBlacklist -> tickefy.redis "jti blacklist" "Redis"
        tickefy.authService.userRepo -> tickefy.postgres "auth_service" "JDBC"
        tickefy.authService.roleRepo -> tickefy.postgres "auth_service" "JDBC"
        tickefy.authService.refreshRepo -> tickefy.postgres "auth_service" "JDBC"

        // =============================================================
        // ===== INVENTORY — relationships
        // =============================================================
        tickefy.apiGateway -> tickefy.inventoryService.reserveApi "Routes /api/inventory/reservations" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.inventoryService.ticketTypeApi "Routes ticket-types" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.inventoryService.limitApi "Routes purchase-limits" "HTTP/JSON"

        tickefy.rabbitMq -> tickefy.inventoryService.orderPaidConsumer "order.paid" "AMQP" "Asynchronous"
        tickefy.rabbitMq -> tickefy.inventoryService.orderReleaseConsumer "order.payment.failed / order.expired" "AMQP" "Asynchronous"

        tickefy.inventoryService.reserveApi -> tickefy.inventoryService.reserveSvc "reserve"
        tickefy.inventoryService.ticketTypeApi -> tickefy.inventoryService.ticketTypeSvc "create/list/availability"
        tickefy.inventoryService.limitApi -> tickefy.inventoryService.limitSvc "remaining limit"
        tickefy.inventoryService.orderPaidConsumer -> tickefy.inventoryService.lifecycleSvc "commit"
        tickefy.inventoryService.orderReleaseConsumer -> tickefy.inventoryService.lifecycleSvc "release"

        tickefy.inventoryService.reserveSvc -> tickefy.inventoryService.redisLua "Lua atomic reserve / compensate"
        tickefy.inventoryService.reserveSvc -> tickefy.inventoryService.reservePersistence "persist reservation (TX)"
        tickefy.inventoryService.reservePersistence -> tickefy.inventoryService.reservationRepo "ticket_reservations"
        tickefy.inventoryService.reservePersistence -> tickefy.inventoryService.inventoryRepo "reserved counters"
        tickefy.inventoryService.ticketTypeSvc -> tickefy.inventoryService.ticketTypeRepo "ticket_types"
        tickefy.inventoryService.ticketTypeSvc -> tickefy.inventoryService.redisLua "seed/availability"
        tickefy.inventoryService.limitSvc -> tickefy.inventoryService.reservationRepo "sum active"
        tickefy.inventoryService.lifecycleSvc -> tickefy.inventoryService.inventoryRepo "commit/release reserved"
        tickefy.inventoryService.lifecycleSvc -> tickefy.inventoryService.redisLua "compensate on release"
        tickefy.inventoryService.devSeed -> tickefy.inventoryService.ticketTypeRepo "seed ticket types"
        tickefy.inventoryService.devSeed -> tickefy.inventoryService.redisLua "seed stock"

        tickefy.inventoryService.redisLua -> tickefy.redis "stock / user-limit / meta (Lua)" "Redis"
        tickefy.inventoryService.ticketTypeRepo -> tickefy.postgres "inventory_service" "JDBC"
        tickefy.inventoryService.inventoryRepo -> tickefy.postgres "inventory_service" "JDBC"
        tickefy.inventoryService.reservationRepo -> tickefy.postgres "inventory_service" "JDBC"

        // =============================================================
        // ===== ORDER — relationships
        // =============================================================
        audience -> tickefy.apiGateway "Create / view orders" "HTTPS"
        tickefy.apiGateway -> tickefy.orderService.orderApi "Routes /api/orders/**" "HTTP/JSON"
        tickefy.rabbitMq -> tickefy.orderService.paymentConsumer "payment.succeeded / payment.failed" "AMQP" "Asynchronous"

        tickefy.orderService.orderApi -> tickefy.orderService.orderSvc "create/resume/get"
        tickefy.orderService.orderSvc -> tickefy.orderService.inventoryClient "reserve (sync, outside TX)"
        tickefy.orderService.orderSvc -> tickefy.orderService.stubPaymentClient "create payment (stub)"
        tickefy.orderService.orderSvc -> tickefy.orderService.orderPersistence "state transitions (short TX)"
        tickefy.orderService.orderPersistence -> tickefy.orderService.stateMachine "assertTransition"
        tickefy.orderService.orderPersistence -> tickefy.orderService.orderRepo "orders"
        tickefy.orderService.orderPersistence -> tickefy.orderService.orderItemRepo "order_items"
        tickefy.orderService.orderPersistence -> tickefy.orderService.outboxRepo "outbox (same TX)"
        tickefy.orderService.paymentConsumer -> tickefy.orderService.orderPersistence "markPaid / markPaymentFailed"
        tickefy.orderService.expireWorker -> tickefy.orderService.orderRepo "find expired"
        tickefy.orderService.expireWorker -> tickefy.orderService.orderPersistence "markExpired"
        tickefy.orderService.outboxPublisher -> tickefy.orderService.outboxRepo "poll PENDING"

        // outbound to other containers
        tickefy.orderService.inventoryClient -> tickefy.inventoryService.reserveApi "Reserve (DIRECT HTTP, not gateway)" "HTTP/JSON" "Synchronous"
        tickefy.orderService.outboxPublisher -> tickefy.rabbitMq "Publish order.paid / payment.failed / expired" "AMQP" "Asynchronous"
        tickefy.orderService.devPayApi -> tickefy.rabbitMq "Publish payment.* (dev only)" "AMQP" "Asynchronous"
        tickefy.orderService.orderRepo -> tickefy.postgres "order_service" "JDBC"
        tickefy.orderService.orderItemRepo -> tickefy.postgres "order_service" "JDBC"
        tickefy.orderService.outboxRepo -> tickefy.postgres "order_service" "JDBC"

        // =============================================================
        // ===== CUSTOMER WEB — relationships
        // =============================================================
        audience -> tickefy.customerWeb.webRouter "Browses, books, views e-tickets" "HTTPS (browser)"
        tickefy.customerWeb.webRouter -> tickefy.customerWeb.webGuard "Guards protected routes"
        tickefy.customerWeb.webRouter -> tickefy.customerWeb.webAuth "Renders auth views"
        tickefy.customerWeb.webRouter -> tickefy.customerWeb.webConcerts "Renders concert views"
        tickefy.customerWeb.webRouter -> tickefy.customerWeb.webBooking "Renders booking/checkout"
        tickefy.customerWeb.webRouter -> tickefy.customerWeb.webOrders "Renders my-orders/payment-result"
        tickefy.customerWeb.webRouter -> tickefy.customerWeb.webTickets "Renders my-tickets"
        tickefy.customerWeb.webGuard -> tickefy.customerWeb.webAuthStore "Reads auth status"
        tickefy.customerWeb.webAuth -> tickefy.customerWeb.webAuthStore "Sets user/token"
        tickefy.customerWeb.webBooking -> tickefy.customerWeb.webSeatMap "Zone selection"
        tickefy.customerWeb.webBooking -> tickefy.customerWeb.webBookingStore "Selection + idempotencyKey"
        tickefy.customerWeb.webOrders -> tickefy.customerWeb.webBookingStore "Reads selection"
        tickefy.customerWeb.webConcerts -> tickefy.customerWeb.webGateways "ticket-types / availability"
        tickefy.customerWeb.webBooking -> tickefy.customerWeb.webGateways "concert / inventory"
        tickefy.customerWeb.webOrders -> tickefy.customerWeb.webGateways "orders + poll"
        tickefy.customerWeb.webTickets -> tickefy.customerWeb.webGateways "tickets"
        tickefy.customerWeb.webAuth -> tickefy.customerWeb.webApiClient "login / refresh / logout"
        tickefy.customerWeb.webGateways -> tickefy.customerWeb.webApiClient "HTTP impl (real mode)"
        tickefy.customerWeb.webApiClient -> tickefy.customerWeb.webAuthStore "Reads accessToken (Bearer)"
        tickefy.customerWeb.webApiClient -> tickefy.sharedPkg "createApiClient + envelope/ErrorCode"
        tickefy.customerWeb.webGateways -> tickefy.sharedPkg "gateway interfaces + selectGateway"
        tickefy.customerWeb.webApiClient -> tickefy.apiGateway "Calls /api/** (dev: Next proxy -> services)" "HTTPS/JSON" "Synchronous"

        // =============================================================
        // ===== ADMIN WEB — relationships
        // =============================================================
        organizer -> tickefy.adminWeb.adminRouter "Manages concerts, tickets, imports" "HTTPS (browser)"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminGuard "Role-guards routes"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminAuth "Renders login"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminConcerts "Renders concert mgmt"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminTicketTypes "Renders ticket-type mgmt"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminOrders "Renders orders"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminPayments "Renders payments"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminDashboard "Renders dashboard"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminAiBio "Renders AI-bio"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminCsv "Renders CSV import"
        tickefy.adminWeb.adminGuard -> tickefy.adminWeb.adminAuthStore "Checks role"
        tickefy.adminWeb.adminRouter -> tickefy.adminWeb.adminShellStore "Sidebar state"
        tickefy.adminWeb.adminConcerts -> tickefy.adminWeb.adminGateways "concerts CRUD"
        tickefy.adminWeb.adminTicketTypes -> tickefy.adminWeb.adminGateways "ticket-types"
        tickefy.adminWeb.adminOrders -> tickefy.adminWeb.adminGateways "orders"
        tickefy.adminWeb.adminPayments -> tickefy.adminWeb.adminGateways "payments"
        tickefy.adminWeb.adminDashboard -> tickefy.adminWeb.adminGateways "revenue / tickets"
        tickefy.adminWeb.adminAiBio -> tickefy.adminWeb.adminGateways "upload / job"
        tickefy.adminWeb.adminCsv -> tickefy.adminWeb.adminGateways "upload / job"
        tickefy.adminWeb.adminGateways -> tickefy.adminWeb.adminApiClient "HTTP impl (provisional)"
        tickefy.adminWeb.adminGateways -> tickefy.sharedPkg "gateway interfaces + selectGateway"
        tickefy.adminWeb.adminApiClient -> tickefy.sharedPkg "createApiClient + envelope/ErrorCode"
        tickefy.adminWeb.adminApiClient -> tickefy.apiGateway "Calls :8080/api/** (real auth deferred)" "HTTPS/JSON" "Synchronous"
    }

    views {
        component tickefy.authService "L3-Auth" {
            title "C4 L3 - Auth Service components"
            include *
            autoLayout lr 300 160
        }
        component tickefy.inventoryService "L3-Inventory" {
            title "C4 L3 - Inventory Service components"
            include *
            autoLayout lr 300 160
        }
        component tickefy.orderService "L3-Order" {
            title "C4 L3 - Order Service components"
            include *
            autoLayout lr 300 160
        }
        component tickefy.customerWeb "L3-CustomerWeb" {
            title "C4 L3 - Customer Web components"
            include *
            autoLayout lr 300 160
        }
        component tickefy.adminWeb "L3-AdminWeb" {
            title "C4 L3 - Admin Web components"
            include *
            autoLayout lr 300 160
        }

        styles {
            element "Element" {
                shape RoundedBox
                background #438dd5
                color #ffffff
                stroke #1f2937
                strokeWidth 2
                fontSize 20
            }
            element "Person" {
                shape Person
                background #08427b
                color #ffffff
            }
            element "Software System" {
                background #1168bd
                color #ffffff
            }
            element "InternalSystem" {
                background #1168bd
                color #ffffff
            }
            element "Container" {
                background #438dd5
                color #ffffff
            }
            element "Component" {
                shape Component
                background #85bbf0
                color #111827
            }
            element "API" {
                shape Hexagon
            }
            element "InboundAdapter" {
                background #0ea5e9
                color #ffffff
            }
            element "ApplicationComponent" {
                background #85bbf0
                color #111827
            }
            element "DomainComponent" {
                background #22c55e
                color #111827
            }
            element "OutboundAdapter" {
                background #f59e0b
                color #111827
            }
            element "Repository" {
                shape Cylinder
                background #16a34a
                color #ffffff
            }
            element "BackgroundJob" {
                shape Robot
                background #6366f1
                color #ffffff
            }
            element "DevStub" {
                background #cbd5e1
                color #1f2937
                border dashed
            }
            element "Route" {
                background #0ea5e9
                color #ffffff
            }
            element "FeatureModule" {
                background #85bbf0
                color #111827
            }
            element "Store" {
                shape Folder
                background #a78bfa
                color #1f2937
            }
            element "ApiClient" {
                background #f59e0b
                color #111827
            }
            element "DataGateway" {
                background #f59e0b
                color #111827
            }
            element "SharedLib" {
                shape Folder
                background #94a3b8
                color #1f2937
            }
            element "Database" {
                shape Cylinder
                background #2e7d32
                color #ffffff
            }
            element "Cache" {
                shape Cylinder
                background #d97706
                color #ffffff
            }
            element "MessageBroker" {
                shape Pipe
                background #7b1fa2
                color #ffffff
            }
            relationship "Relationship" {
                thickness 2
                color #64748b
                routing Orthogonal
                fontSize 16
            }
            relationship "Synchronous" {
                style solid
                color #2563eb
            }
            relationship "Asynchronous" {
                style dashed
                color #7b1fa2
            }
        }
    }
}
