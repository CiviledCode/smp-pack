package dev.grxm.badges;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.fabric.impl.resource.loader.ModResourcePackCreator;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Fabric provider + vanilla selection, without MinecraftClient or a window. */
class BadgeResourcesTest {
    @TempDir Path temporary;

    @Test void canonicalResourcesSurviveEmptySelectionAndRepositoryReload() throws Exception {
        Path root = Path.of(System.getProperty("badge.root"));
        Path jar = root.resolve("assets/mods/grxm-team-badges-1.0.0.jar");
        SharedConstants.tryDetectVersion();
        var loaderMods = FabricLoaderImpl.INSTANCE.getModsInternal();
        assertTrue(loaderMods.isEmpty(), "This harness must run isolated from other mods");
        try (var archive = new ZipFile(jar.toFile());
             var input = archive.getInputStream(archive.getEntry("fabric.mod.json"))) {
            var metadata = ModMetadataParser.parseMetadata(input, jar.toString(), List.of(),
                new VersionOverrides(), new DependencyOverrides(temporary), false);
            assertTrue(metadata.loadsInEnvironment(EnvType.CLIENT));
            assertFalse(metadata.loadsInEnvironment(EnvType.SERVER));
            // Fabric's factory is package-private; reflect only fixture construction.
            var factory = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class,
                LoaderModMetadata.class, boolean.class, java.util.Collection.class);
            factory.setAccessible(true);
            var candidate = (ModCandidateImpl) factory.invoke(null, List.of(jar), metadata, false, List.of());
            loaderMods.add(new ModContainerImpl(candidate));
            try {
                var repository = new PackRepository(ModResourcePackCreator.CLIENT_RESOURCE_PACK_PROVIDER);
                repository.reload();
                assertNotNull(repository.getPack("fabric"));
                assertTrue(repository.getPack("fabric").isRequired());
                for (int launch = 0; launch < 2; launch++) {
                    // No options.txt selection: vanilla must still insert required mod resources.
                    repository.setSelected(List.of());
                    assertTrue(repository.getSelectedIds().contains("fabric"));
                    var packs = repository.openAllSelected();
                    try (var sources = Files.walk(root.resolve("assets/team-badges/assets"))) {
                        for (Path source : sources.filter(Files::isRegularFile).toList()) {
                            String relative = root.resolve("assets/team-badges/assets").relativize(source).toString().replace('\\', '/');
                            int separator = relative.indexOf('/');
                            var id = new ResourceLocation(relative.substring(0, separator), relative.substring(separator + 1));
                            var supplier = packs.get(0).getResource(PackType.CLIENT_RESOURCES, id);
                            assertNotNull(supplier, "Missing " + id);
                            try (var stream = supplier.get()) {
                                assertArrayEquals(Files.readAllBytes(source), stream.readAllBytes(), id.toString());
                            }
                        }
                    } finally {
                        packs.forEach(PackResources::close);
                    }
                    repository.reload();
                }
                System.out.println("PASS: actual Fabric required profile, empty selection/reload, all canonical font/texture bytes; client-only metadata");
            } finally { loaderMods.clear(); }
        }
        // Negative control: without the installed artifact there is no Fabric resource profile.
        var withoutMod = new PackRepository(ModResourcePackCreator.CLIENT_RESOURCE_PACK_PROVIDER);
        withoutMod.reload();
        assertNull(withoutMod.getPack("fabric"));
    }
}
