workspace "Tickefy - C4 Level 3" "Component template that zooms into one backend service." {

    !identifiers hierarchical

    model {
        // =============================================================
        // ACTOR AND SURROUNDING CONTAINERS
        // Level 3 chỉ zoom vào MỘT container/service.
        // Các service khác chỉ xuất hiện như dependency bên ngoài container đó.
        // =============================================================
        audience = person "Audience" "Purchases concert tickets."

        tickefy = softwareSystem "Tickefy" "Concert ticketing and check-in platform." {
            tags "InternalSystem"

            apiGateway = container "API Gateway" "Routes requests, verifies JWT, rate limits, and propagates request IDs." "Spring Cloud Gateway/Nginx" {
                tags "API"
            }

            inventoryService = container "Inventory Service" "Owns ticket stock, reservations, and per-user purchase limits." "Spring Boot"
            paymentService = container "Payment Service" "Creates payment transactions and handles provider callbacks." "Spring Boot"

            // =========================================================
            // CONTAINER IN SCOPE: ORDER SERVICE
            // Đổi tên container và component để áp dụng cho service khác.
            // Không biểu diễn từng class/DTO/entity thành component.
            // =========================================================
            orderService = container "Order Service" "Orchestrates ticket purchase and order lifecycle." "Spring Boot" {

                group "Inbound Adapters" {
                    orderApi = component "Order API" "Receives order commands and queries from the API Gateway." "Spring MVC REST Controller" {
                        tags "InboundAdapter"
                    }

                    paymentResultConsumer = component "Payment Result Consumer" "Consumes PaymentSucceeded and PaymentFailed events idempotently." "RabbitMQ Consumer" {
                        tags "InboundAdapter"
                    }

                    expirationJob = component "Order Expiration Job" "Finds unpaid orders that have exceeded their payment deadline." "Scheduled Job" {
                        tags "BackgroundJob"
                    }
                }

                group "Application Layer" {
                    createOrderUseCase = component "Create Order Use Case" "Coordinates validation, reservation, persistence, and payment creation." "Application Service" {
                        tags "ApplicationComponent"
                    }

                    handlePaymentResultUseCase = component "Handle Payment Result Use Case" "Applies payment results and publishes the resulting order event." "Application Service" {
                        tags "ApplicationComponent"
                    }

                    expireOrderUseCase = component "Expire Order Use Case" "Expires unpaid orders and releases their reservations." "Application Service" {
                        tags "ApplicationComponent"
                    }
                }

                group "Domain Layer" {
                    orderDomainService = component "Order Domain Service" "Enforces the order state machine and business invariants." "Domain Service" {
                        tags "DomainComponent"
                    }
                }

                group "Outbound Adapters" {
                    inventoryClient = component "Inventory Client" "Calls Inventory Service to reserve, commit, or release tickets." "HTTP Client" {
                        tags "OutboundAdapter"
                    }

                    paymentClient = component "Payment Client" "Calls Payment Service to create a payment transaction." "HTTP Client" {
                        tags "OutboundAdapter"
                    }

                    orderRepository = component "Order Repository" "Persists orders, order items, and status history." "Spring Data JPA" {
                        tags "Repository"
                    }

                    eventPublisher = component "Order Event Publisher" "Publishes OrderPaid, OrderExpired, and payment-failure events." "RabbitMQ Publisher" {
                        tags "OutboundAdapter"
                    }
                }
            }

            rabbitMq = container "Message Broker" "Distributes domain events among services." "RabbitMQ" {
                tags "MessageBroker"
            }

            postgres = container "PostgreSQL Schemas" "Stores independently owned service schemas." "PostgreSQL" {
                tags "Database"
            }
        }

        // =============================================================
        // EXTERNAL RELATIONSHIPS TO THE CONTAINER/COMPONENTS IN SCOPE
        // =============================================================
        audience -> tickefy.apiGateway "Creates and queries orders" "HTTPS/JSON"
        tickefy.apiGateway -> tickefy.orderService.orderApi "Invokes order APIs" "HTTP/JSON"

        tickefy.rabbitMq -> tickefy.orderService.paymentResultConsumer "Delivers PaymentSucceeded and PaymentFailed" "AMQP" {
            tags "Asynchronous"
        }

        // =============================================================
        // INTERNAL COMPONENT RELATIONSHIPS
        // =============================================================
        tickefy.orderService.orderApi -> tickefy.orderService.createOrderUseCase "Executes create-order commands"
        tickefy.orderService.createOrderUseCase -> tickefy.orderService.orderDomainService "Applies order rules and valid state transitions"
        tickefy.orderService.createOrderUseCase -> tickefy.orderService.inventoryClient "Requests a ticket reservation"
        tickefy.orderService.createOrderUseCase -> tickefy.orderService.paymentClient "Requests payment creation"
        tickefy.orderService.createOrderUseCase -> tickefy.orderService.orderRepository "Persists the order and its items"

        tickefy.orderService.paymentResultConsumer -> tickefy.orderService.handlePaymentResultUseCase "Delegates payment-result processing"
        tickefy.orderService.handlePaymentResultUseCase -> tickefy.orderService.orderRepository "Loads and saves the order"
        tickefy.orderService.handlePaymentResultUseCase -> tickefy.orderService.orderDomainService "Applies the payment-result transition"
        tickefy.orderService.handlePaymentResultUseCase -> tickefy.orderService.eventPublisher "Publishes the resulting order event"

        tickefy.orderService.expirationJob -> tickefy.orderService.expireOrderUseCase "Triggers expiration processing"
        tickefy.orderService.expireOrderUseCase -> tickefy.orderService.orderRepository "Finds and saves expired orders"
        tickefy.orderService.expireOrderUseCase -> tickefy.orderService.orderDomainService "Transitions orders to EXPIRED"
        tickefy.orderService.expireOrderUseCase -> tickefy.orderService.inventoryClient "Releases expired reservations"
        tickefy.orderService.expireOrderUseCase -> tickefy.orderService.eventPublisher "Publishes OrderExpired"

        // =============================================================
        // OUTBOUND DEPENDENCIES
        // =============================================================
        tickefy.orderService.inventoryClient -> tickefy.inventoryService "Calls reservation APIs" "HTTP/JSON" {
            tags "Synchronous"
        }

        tickefy.orderService.paymentClient -> tickefy.paymentService "Calls payment APIs" "HTTP/JSON" {
            tags "Synchronous"
        }

        tickefy.orderService.orderRepository -> tickefy.postgres "Reads/writes order_schema" "JDBC"

        tickefy.orderService.eventPublisher -> tickefy.rabbitMq "Publishes order domain events" "AMQP" {
            tags "Asynchronous"
        }
    }

    views {
        component tickefy.orderService "L3-OrderService-Components" {
            title "C4 Level 3 - Order Service Components"
            description "Shows the major components inside Order Service and their external dependencies."
            include *
            autoLayout lr 300 180
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

            element "Database" {
                shape Cylinder
                background #2e7d32
                color #ffffff
            }

            element "MessageBroker" {
                shape Pipe
                background #7b1fa2
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
