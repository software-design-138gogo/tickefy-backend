workspace "Tickefy" "C4 Level 1 - System Context: users, the Tickefy platform, and its external dependencies." {

    !identifiers hierarchical

    model {
        // =============================================================
        // PEOPLE  -  chi vai tro thuc su tuong tac voi he thong
        // =============================================================
        audience     = person "Audience"      "Discovers concerts, purchases tickets, and views e-tickets."
        organizer    = person "Organizer"     "Creates and manages concerts, ticket types, and import jobs."
        checkinStaff = person "Check-in Staff" "Scans tickets and syncs online/offline check-in data."

        // RBAC he thong co 4 role. Bo comment 2 dong (person + relationship)
        // neu muon tach Admin khoi Organizer o Level 1:
        // admin     = person "Admin"          "Manages users/roles, cancels concerts, triggers refunds."

        // =============================================================
        // SYSTEM IN SCOPE  -  Level 1 xem Tickefy nhu mot khoi duy nhat
        // (khong dua service / DB / Redis / RabbitMQ vao day)
        // =============================================================
        tickefy = softwareSystem "Tickefy" "Concert ticketing platform: ticket sales, e-tickets, online/offline check-in, AI artist bios, and CSV VIP ingestion." {
            tags "InternalSystem"
        }

        // =============================================================
        // EXTERNAL SOFTWARE SYSTEMS
        // =============================================================
        paymentProvider = softwareSystem "Payment Provider" "External VNPAY/MoMo payment gateway." {
            tags "ExternalSystem"
        }
        emailProvider = softwareSystem "Email Provider" "External transactional email/push delivery provider." {
            tags "ExternalSystem"
        }
        aiProvider = softwareSystem "AI Provider" "External generative AI API used to summarize artist press kits." {
            tags "ExternalSystem"
        }

        // =============================================================
        // RELATIONSHIPS  -  mo ta hanh dong nghiep vu, tranh chi ghi "Uses"
        // =============================================================
        audience     -> tickefy "Discovers concerts, buys tickets, accesses e-tickets" "HTTPS"
        organizer    -> tickefy "Manages concerts, ticket configs, and import jobs" "HTTPS"
        checkinStaff -> tickefy "Performs online and offline ticket check-in" "HTTPS online; offline: pre-downloaded snapshot + local SQLite, synced on reconnect"
        // admin     -> tickefy "Manages users/roles, cancels concerts, triggers refunds" "HTTPS"

        tickefy -> paymentProvider "Creates payments and receives payment results" "HTTPS"
        tickefy -> emailProvider   "Sends transactional notifications" "SMTP/HTTPS"
        tickefy -> aiProvider      "Requests artist-biography generation" "HTTPS"
    }

    views {
        systemContext tickefy "L1-SystemContext" {
            title "C4 Level 1 - Tickefy System Context"
            description "Tickefy, its users, and external software-system dependencies."
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
