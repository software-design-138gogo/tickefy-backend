workspace "Tickefy - C4 Level 1" "System Context template showing users, Tickefy, and external systems." {

    !identifiers hierarchical

    model {
        // =============================================================
        // PEOPLE
        // Chỉ khai báo các vai trò thực sự tương tác với hệ thống.
        // =============================================================
        audience = person "Audience" "Discovers concerts, purchases tickets, and views e-tickets."
        organizer = person "Organizer" "Creates and manages concerts, ticket configurations, and import jobs."
        checkinStaff = person "Check-in Staff" "Scans tickets and synchronizes online/offline check-in data."

        // =============================================================
        // SYSTEM IN SCOPE
        // Level 1 chỉ xem Tickefy như một khối duy nhất.
        // Không đưa service, database, Redis hoặc RabbitMQ vào đây.
        // =============================================================
        tickefy = softwareSystem "Tickefy" "Concert ticketing platform supporting ticket sales, e-tickets, online/offline check-in, AI artist bios, and CSV VIP ingestion." {
            tags "InternalSystem"
        }

        // =============================================================
        // EXTERNAL SOFTWARE SYSTEMS
        // Chỉ khai báo hệ thống nằm ngoài phạm vi Tickefy.
        // =============================================================
        paymentProvider = softwareSystem "Payment Provider" "External VNPAY/MoMo payment gateway." {
            tags "ExternalSystem"
        }

        emailProvider = softwareSystem "Email Provider" "External transactional email delivery provider." {
            tags "ExternalSystem"
        }

        aiProvider = softwareSystem "AI Provider" "External generative AI API used to summarize artist press kits." {
            tags "ExternalSystem"
        }

        // =============================================================
        // RELATIONSHIPS
        // Relationship phải mô tả hành động/nghiệp vụ, tránh chỉ ghi "Uses".
        // =============================================================
        audience -> tickefy "Discovers concerts, purchases tickets, and accesses e-tickets" "HTTPS"
        organizer -> tickefy "Manages concerts, artists, ticket configurations, and import jobs" "HTTPS"
        checkinStaff -> tickefy "Performs online and offline ticket check-in" "HTTPS/Local storage"

        tickefy -> paymentProvider "Creates payments and receives payment results" "HTTPS"
        tickefy -> emailProvider "Sends transactional email notifications" "HTTPS/SMTP"
        tickefy -> aiProvider "Requests artist-biography generation" "HTTPS"
    }

    views {
        systemContext tickefy "L1-SystemContext" {
            title "C4 Level 1 - Tickefy System Context"
            description "Shows Tickefy, its users, and external software-system dependencies."
            include *
            autoLayout lr 300 200
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

            relationship "Relationship" {
                thickness 2
                color #64748b
                routing Orthogonal
                fontSize 16
            }
        }
    }
}
