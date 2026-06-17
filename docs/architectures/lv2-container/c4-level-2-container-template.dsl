workspace "Tickefy - C4 Level 2" "Container template showing Tickefy applications, services, data stores, and infrastructure dependencies." {

    !identifiers hierarchical

    model {
        // =============================================================
        // PEOPLE
        // =============================================================
        audience = person "Audience" "Discovers concerts, purchases tickets, and views e-tickets."
        organizer = person "Organizer" "Creates and manages concerts and operational data."
        checkinStaff = person "Check-in Staff" "Performs online and offline check-in."

        // =============================================================
        // EXTERNAL SYSTEMS
        // =============================================================
        paymentProvider = softwareSystem "Payment Provider" "External VNPAY/MoMo payment gateway." {
            tags "ExternalSystem"
        }

        emailProvider = softwareSystem "Email Provider" "External transactional email delivery provider." {
            tags "ExternalSystem"
        }

        aiProvider = softwareSystem "AI Provider" "External generative AI API." {
            tags "ExternalSystem"
        }

        // =============================================================
        // SYSTEM IN SCOPE AND ITS CONTAINERS
        // Trong C4, container là application/service/data store có ranh giới
        // triển khai rõ ràng; không nhất thiết là Docker container.
        // =============================================================
        tickefy = softwareSystem "Tickefy" "Concert ticketing and check-in platform." {
            tags "InternalSystem"

            group "Client Applications" {
                customerWeb = container "Customer Web" "Allows audiences to discover concerts, buy tickets, and view QR e-tickets." "React/Next.js" {
                    tags "WebBrowser"
                }

                adminWeb = container "Admin Web" "Allows organizers and administrators to manage concerts and operational data." "React/Next.js" {
                    tags "WebBrowser"
                }

                checkinMobile = container "Check-in Mobile App" "Allows staff to scan tickets offline or online and synchronize scan batches." "React Native/Flutter" {
                    tags "MobileApp"
                }
            }

            group "Edge" {
                apiGateway = container "API Gateway" "Single entry point for routing, edge JWT verification, rate limiting, CORS, and request tracing. Downstream services still verify JWT and enforce business authorization." "Spring Cloud Gateway/Nginx" {
                    tags "API"
                }
            }

            group "Business Services" {
                authService = container "Auth Service" "Manages users, authentication, access/refresh tokens, and RBAC." "Spring Boot"
                eventService = container "Event Service" "Manages concerts, artists, venues, event lifecycle, and seating-map metadata." "Spring Boot"
                inventoryService = container "Inventory Service" "Manages ticket types, stock, reservations, per-user limits, and anti-over-selling." "Spring Boot"
                orderService = container "Order Service" "Orchestrates ticket purchase, order state transitions, expiration, and payment results." "Spring Boot"
                paymentService = container "Payment Service" "Creates payment transactions, handles callbacks, idempotency, circuit breaker, and reconciliation." "Spring Boot"
                notificationService = container "Notification Service" "Sends email and in-app notifications asynchronously." "Spring Boot"
                ticketService = container "E-Ticket Service" "Issues ticket instances and QR tokens and manages ticket lifecycle." "Spring Boot"
                checkinService = container "Check-in Service" "Verifies tickets, records check-ins, creates snapshots, and resolves offline-sync conflicts." "Spring Boot"
                aiBioService = container "AI Bio Service" "Processes PDF press kits asynchronously and generates artist biographies." "Spring Boot/Python Worker"
                csvService = container "CSV Ingestion Service" "Validates, deduplicates, and imports VIP guest CSV files in background jobs." "Spring Boot"
            }

            group "Data and Messaging" {
                redis = container "Redis" "Stores cache, rate-limit state, reservations, idempotency keys, and token blacklist." "Redis" {
                    tags "Cache"
                }

                rabbitMq = container "Message Broker" "Distributes domain events between independently deployable services." "RabbitMQ" {
                    tags "MessageBroker"
                }

                postgres = container "PostgreSQL Schemas" "One local instance with an independently owned schema per service and no cross-service queries." "PostgreSQL" {
                    tags "Database"
                }

                objectStorage = container "Object Storage" "Stores seating-map SVGs, PDF press kits, CSV files, and generated reports." "MinIO/S3" {
                    tags "ObjectStorage"
                }
            }
        }

        // =============================================================
        // CLIENT -> SYSTEM RELATIONSHIPS
        // =============================================================
        audience -> tickefy.customerWeb "Uses" "HTTPS"
        organizer -> tickefy.adminWeb "Uses" "HTTPS"
        checkinStaff -> tickefy.checkinMobile "Uses" "HTTPS/Local storage"

        tickefy.customerWeb -> tickefy.apiGateway "Calls public APIs" "HTTPS/JSON"
        tickefy.adminWeb -> tickefy.apiGateway "Calls organizer and admin APIs" "HTTPS/JSON"
        tickefy.checkinMobile -> tickefy.apiGateway "Calls check-in and synchronization APIs" "HTTPS/JSON"

        // =============================================================
        // API GATEWAY ROUTING
        // Có thể rút gọn các quan hệ này nếu sơ đồ quá dày.
        // =============================================================
        tickefy.apiGateway -> tickefy.authService "Routes authentication and user requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.eventService "Verifies JWT at edge, forwards Authorization, routes concert requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.inventoryService "Verifies JWT at edge, forwards Authorization, routes ticket availability requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.orderService "Verifies JWT at edge, forwards Authorization, routes order requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.paymentService "Verifies JWT at edge, forwards Authorization, routes payment and callback requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.notificationService "Verifies JWT at edge, forwards Authorization, routes notification requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.ticketService "Verifies JWT at edge, forwards Authorization, routes e-ticket requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.checkinService "Verifies JWT at edge, forwards Authorization, routes check-in requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.aiBioService "Verifies JWT at edge, forwards Authorization, routes AI-bio job requests" "HTTP/JSON"
        tickefy.apiGateway -> tickefy.csvService "Verifies JWT at edge, forwards Authorization, routes VIP-import job requests" "HTTP/JSON"

        // =============================================================
        // SYNCHRONOUS SERVICE-TO-SERVICE COMMUNICATION
        // Chỉ dùng khi caller cần kết quả ngay.
        // =============================================================
        tickefy.orderService -> tickefy.inventoryService "Reserves, commits, and releases tickets" "HTTP/JSON" {
            tags "Synchronous"
        }

        tickefy.orderService -> tickefy.paymentService "Creates payment transactions" "HTTP/JSON" {
            tags "Synchronous"
        }

        tickefy.checkinService -> tickefy.ticketService "Verifies e-tickets online" "HTTP/JSON" {
            tags "Synchronous"
        }

        tickefy.csvService -> tickefy.eventService "Validates concert identifiers" "HTTP/JSON" {
            tags "Synchronous"
        }

        // =============================================================
        // EXTERNAL INTEGRATIONS
        // =============================================================
        tickefy.paymentService -> paymentProvider "Creates payment requests and verifies provider results" "HTTPS"
        paymentProvider -> tickefy.apiGateway "Sends payment callbacks and webhooks" "HTTPS"
        tickefy.notificationService -> emailProvider "Delivers email notifications" "HTTPS/SMTP"
        tickefy.aiBioService -> aiProvider "Generates artist-biography summaries" "HTTPS"

        // =============================================================
        // ASYNCHRONOUS COMMUNICATION
        // Các service publish/consume qua RabbitMQ; consumer phải idempotent.
        // =============================================================
        tickefy.orderService -> tickefy.rabbitMq "Publishes order events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.paymentService -> tickefy.rabbitMq "Publishes payment events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.eventService -> tickefy.rabbitMq "Publishes concert lifecycle events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.ticketService -> tickefy.rabbitMq "Publishes ticket events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.checkinService -> tickefy.rabbitMq "Publishes check-in events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.aiBioService -> tickefy.rabbitMq "Publishes ArtistBioGenerated" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.csvService -> tickefy.rabbitMq "Publishes VipGuestImportCompleted" "AMQP" {
            tags "Asynchronous"
        }

        tickefy.rabbitMq -> tickefy.orderService "Delivers payment and concert events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.rabbitMq -> tickefy.inventoryService "Delivers order and concert events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.rabbitMq -> tickefy.notificationService "Delivers notification-triggering events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.rabbitMq -> tickefy.ticketService "Delivers OrderPaid and ConcertCancelled" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.rabbitMq -> tickefy.checkinService "Delivers ticket and VIP-import events" "AMQP" {
            tags "Asynchronous"
        }
        tickefy.rabbitMq -> tickefy.eventService "Delivers ArtistBioGenerated" "AMQP" {
            tags "Asynchronous"
        }

        // =============================================================
        // REDIS USAGE
        // =============================================================
        tickefy.apiGateway -> tickefy.redis "Applies distributed rate limiting" "Redis protocol"
        tickefy.authService -> tickefy.redis "Stores token blacklist and authorization cache" "Redis protocol"
        tickefy.eventService -> tickefy.redis "Caches concert data" "Redis protocol"
        tickefy.inventoryService -> tickefy.redis "Maintains atomic reservation and stock counters" "Redis/Lua"
        tickefy.orderService -> tickefy.redis "Stores order idempotency and temporary state" "Redis protocol"
        tickefy.paymentService -> tickefy.redis "Stores payment idempotency and circuit-breaker state" "Redis protocol"

        // =============================================================
        // DATABASE OWNERSHIP
        // Mỗi service chỉ đọc/ghi schema của chính mình.
        // =============================================================
        tickefy.authService -> tickefy.postgres "Reads/writes auth_schema" "JDBC"
        tickefy.eventService -> tickefy.postgres "Reads/writes event_schema" "JDBC"
        tickefy.inventoryService -> tickefy.postgres "Reads/writes inventory_schema" "JDBC"
        tickefy.orderService -> tickefy.postgres "Reads/writes order_schema" "JDBC"
        tickefy.paymentService -> tickefy.postgres "Reads/writes payment_schema" "JDBC"
        tickefy.notificationService -> tickefy.postgres "Reads/writes notification_schema" "JDBC"
        tickefy.ticketService -> tickefy.postgres "Reads/writes ticket_schema" "JDBC"
        tickefy.checkinService -> tickefy.postgres "Reads/writes checkin_schema" "JDBC"
        tickefy.aiBioService -> tickefy.postgres "Reads/writes ai_bio_schema" "JDBC"
        tickefy.csvService -> tickefy.postgres "Reads/writes csv_schema" "JDBC"

        // =============================================================
        // OBJECT STORAGE
        // =============================================================
        tickefy.eventService -> tickefy.objectStorage "Stores and retrieves seating-map SVGs" "S3 API"
        tickefy.aiBioService -> tickefy.objectStorage "Reads PDF press kits and stores extracted artifacts" "S3 API"
        tickefy.csvService -> tickefy.objectStorage "Reads CSV files and stores error reports" "S3 API"
    }

    views {
        container tickefy "L2-Containers" {
            title "C4 Level 2 - Tickefy Containers"
            description "Shows client applications, API Gateway, business services, shared infrastructure, and external dependencies."
            include *
            autoLayout lr 350 200
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

            element "ExternalSystem" {
                background #999999
                color #ffffff
                border dashed
            }

            element "Container" {
                background #438dd5
                color #ffffff
            }

            element "WebBrowser" {
                shape WebBrowser
            }

            element "MobileApp" {
                shape MobileDevicePortrait
            }

            element "API" {
                shape Hexagon
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

            element "ObjectStorage" {
                shape Bucket
                background #455a64
                color #ffffff
            }

            element "Boundary" {
                stroke #64748b
                strokeWidth 2
                color #334155
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
