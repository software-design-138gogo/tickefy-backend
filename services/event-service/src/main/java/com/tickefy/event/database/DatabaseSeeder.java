package com.tickefy.event.database;

import com.tickefy.event.modules.artist.Artist;
import com.tickefy.event.modules.artist.ArtistRepository;
import com.tickefy.event.modules.concert.Concert;
import com.tickefy.event.modules.concert.ConcertRepository;
import com.tickefy.event.modules.concert.ConcertStatus;
import com.tickefy.event.modules.concert.ConcertZone;
import com.tickefy.event.modules.venue.Venue;
import com.tickefy.event.modules.venue.VenueRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseSeeder implements CommandLineRunner {

    private final ArtistRepository artistRepository;
    private final VenueRepository venueRepository;
    private final ConcertRepository concertRepository;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (artistRepository.count() > 0) {
            log.info("Database already contains artists. Skipping seed data.");
            return;
        }

        log.info("Seeding initial data for event-service...");

        // 1. Create Artists
        Artist hieuThuHai = createArtist("HIEUTHUHAI", "Rapper nổi bật của Việt Nam.");
        Artist isaac = createArtist("Isaac", "Nam ca sĩ với phong cách lịch lãm.");
        Artist lanNgoc = createArtist("Ninh Dương Lan Ngọc", "Ngọc nữ màn ảnh Việt.");
        Artist trangPhap = createArtist("Trang Pháp", "Ca sĩ, nhạc sĩ kiêm nhà sản xuất âm nhạc tài năng.");

        artistRepository.saveAll(Set.of(hieuThuHai, isaac, lanNgoc, trangPhap));

        // 2. Create Venues
        Venue qk7 = createVenue("Sân vận động Quân Khu 7", "202 Hoàng Văn Thụ", "TP. Hồ Chí Minh", 25000);
        Venue myDinh = createVenue("Sân vận động Quốc gia Mỹ Đình", "Đường Lê Đức Thọ", "Hà Nội", 40000);
        
        venueRepository.saveAll(Set.of(qk7, myDinh));

        // 3. Create Concerts
        Instant now = Instant.now();
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000000"); // Dummy user ID for createdBy

        Concert atsh = createConcert(
                "Anh Trai Say Hi Concert 2026",
                "Concert quy tụ dàn anh trai cực phẩm.",
                qk7,
                now.plus(30, ChronoUnit.DAYS),
                Set.of(hieuThuHai, isaac),
                adminId
        );
        addZones(atsh, "VIP", "GA");

        Concert atvcg = createConcert(
                "Anh Trai Vượt Chông Gai 2026",
                "Đêm nhạc bùng nổ của các anh tài.",
                myDinh,
                now.plus(45, ChronoUnit.DAYS),
                Set.of(isaac),
                adminId
        );
        addZones(atvcg, "SVIP", "VIP", "GA");

        Concert emXinh = createConcert(
                "Em Xinh Say Hi",
                "Đêm diễn tràn ngập sự ngọt ngào.",
                qk7,
                now.plus(15, ChronoUnit.DAYS),
                Set.of(lanNgoc),
                adminId
        );
        addZones(emXinh, "GA");

        Concert chiDep = createConcert(
                "Chị Đẹp Đạp Gió Rẽ Sóng",
                "Sự kết hợp hoàn hảo của các chị đẹp.",
                myDinh,
                now.plus(60, ChronoUnit.DAYS),
                Set.of(trangPhap, lanNgoc),
                adminId
        );
        addZones(chiDep, "DIAMOND", "VIP", "GA");

        concertRepository.saveAll(Set.of(atsh, atvcg, emXinh, chiDep));

        log.info("Seed data inserted successfully!");
    }

    private Artist createArtist(String name, String bio) {
        Artist artist = new Artist();
        artist.setName(name);
        artist.setBio(bio);
        return artist;
    }

    private Venue createVenue(String name, String address, String city, int capacity) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setAddress(address);
        venue.setCity(city);
        venue.setCapacity(capacity);
        return venue;
    }

    private Concert createConcert(String title, String desc, Venue venue, Instant eventDate, Set<Artist> artists, UUID createdBy) {
        Concert concert = new Concert();
        concert.setTitle(title);
        concert.setDescription(desc);
        concert.setVenue(venue);
        concert.setEventDate(eventDate);
        
        // Setup sale time
        concert.setSaleStartAt(Instant.now().minus(1, ChronoUnit.DAYS));
        concert.setSaleEndAt(eventDate.minus(1, ChronoUnit.DAYS));
        
        concert.setStatus(ConcertStatus.PUBLISHED);
        concert.setCreatedBy(createdBy);
        concert.setArtists(artists);
        return concert;
    }

    private void addZones(Concert concert, String... zoneNames) {
        for (String zoneName : zoneNames) {
            ConcertZone zone = new ConcertZone();
            zone.setConcert(concert);
            zone.setZoneName(zoneName);
            zone.setTicketTypeName("Vé " + zoneName);
            concert.getZones().add(zone);
        }
    }
}
