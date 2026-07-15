package com.tickefy.event.database;

import com.tickefy.event.modules.artist.Artist;
import com.tickefy.event.modules.artist.ArtistRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the artist catalog. The concert + venue + zone catalog it used to create was moved to
 * {@link EventAnchorSeeder} — fixed UUIDs and BARE zone names (SVIP/VIP/CAT1/CAT2/GA) so every concert
 * is inventory-backed and reset-safe. Zones here used a {@code "Vé "} prefix that broke cross-service
 * name-resolution (§6.10), so that path is gone. Artists remain (independent catalog, no venue/concert
 * dependency).
 *
 * <p>Guard {@code artistRepository.count()>0} keeps this idempotent across restarts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseSeeder implements CommandLineRunner {

    private final ArtistRepository artistRepository;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (artistRepository.count() > 0) {
            log.info("Database already contains artists. Skipping seed data.");
            return;
        }

        log.info("Seeding artist catalog for event-service...");

        Artist hieuThuHai = createArtist("HIEUTHUHAI", "Rapper nổi bật của Việt Nam.");
        Artist isaac = createArtist("Isaac", "Nam ca sĩ với phong cách lịch lãm.");
        Artist lanNgoc = createArtist("Ninh Dương Lan Ngọc", "Ngọc nữ màn ảnh Việt.");
        Artist trangPhap = createArtist("Trang Pháp", "Ca sĩ, nhạc sĩ kiêm nhà sản xuất âm nhạc tài năng.");

        artistRepository.saveAll(Set.of(hieuThuHai, isaac, lanNgoc, trangPhap));

        log.info("Artist catalog inserted successfully!");
    }

    private Artist createArtist(String name, String bio) {
        Artist artist = new Artist();
        artist.setName(name);
        artist.setBio(bio);
        return artist;
    }
}
