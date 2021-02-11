/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.cadixdev.vignette;

import static java.util.Arrays.asList;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minecraftforge.lex.ConstructorInjector;
import net.minecraftforge.lex.EnhancedRemappingTransformer;

import org.cadixdev.atlas.Atlas;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingFormat;
import org.cadixdev.lorenz.io.MappingFormats;
import org.cadixdev.vignette.util.MappingFormatValueConverter;
import org.cadixdev.vignette.util.PathValueConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Main-Class behind Vignette.
 *
 * @author Jamie Mansfield
 * @since 0.1.0
 */
public final class VignetteMain {

    private static final String VERSION = "0.2.0-SNAPSHOT";

    public static void main(final String[] args) {

        final OptionParser parser = new OptionParser();

        // Modes
        final OptionSpec<Void> helpSpec = parser.acceptsAll(asList("?", "help"), "Show the help").forHelp();
        final OptionSpec<Void> versionSpec = parser.accepts("version", "Shows the version");

        // Options
        final OptionSpec<Path> jarInSpec = parser.acceptsAll(asList("jar-in", "i"), "The jar to remap/map")
                .withRequiredArg()
                .withValuesConvertedBy(PathValueConverter.INSTANCE);
        final OptionSpec<Path> jarOutSpec = parser.acceptsAll(asList("jar-out", "o"), "The output jar")
                .withRequiredArg()
                .withValuesConvertedBy(PathValueConverter.INSTANCE);
        final OptionSpec<MappingFormat> mappingFormatSpec = parser.acceptsAll(asList("mapping-format", "f"), "The mapping format")
                .withRequiredArg()
                .withValuesConvertedBy(MappingFormatValueConverter.INSTANCE)
                .defaultsTo(MappingFormats.SRG);
        final OptionSpec<Path> mappingsSpec = parser.acceptsAll(asList("mappings", "m"), "The mappings to remap with")
                .withRequiredArg()
                .withValuesConvertedBy(PathValueConverter.INSTANCE);
        final OptionSpec<Void> reverseSpec = parser.acceptsAll(asList("reverse", "r"), "Reverses the mappings when applied");
        final OptionSpec<Integer> threadsSpec = parser.acceptsAll(asList("threads", "t"), "Number of threads to use when remapping")
                .withOptionalArg()
                .ofType(Integer.class);
        final OptionSpec<Path> librarySpec = parser.acceptsAll(asList("library", "l"), "Library to add to the classpath for constructing inheritance")
                .withOptionalArg()
                .withValuesConvertedBy(PathValueConverter.INSTANCE);
        final OptionSpec<Void> ffmetaSpec = parser.acceptsAll(asList("fernflower-meta", "f"), "Generate special metadata file for ForgeFlower that will name abstract method arguments during decompilation");
        final OptionSpec<Void> ctrSpec = parser.acceptsAll(asList("create-inits", "c"), "Automatically inject synthetic <init> functions for classes with final fields and no constructors.");

        final OptionSet options;
        try {
            options = parser.parse(enhanceArgs(args));
        }
        catch (final OptionException ex) {
            System.err.println("Failed to parse OptionSet! Exiting...");
            ex.printStackTrace(System.err);
            System.exit(-1);
            return;
        }

        if (options.has(helpSpec)) {
            try {
                parser.printHelpOn(System.out);
            } catch (final IOException ex) {
                System.err.println("Failed to print help information!");
                ex.printStackTrace(System.err);
                System.exit(-1);
            }
        }
        else if (options.has(versionSpec)) {
            asList(
                    "Vignette " + VERSION,
                    "Copyright (c) 2019-2021 Jamie Mansfield <https://www.jamiemansfield.me/>",
                    // The following is adapted from a similar statement Mozilla make for Firefox
                    // See about:rights
                    "Vignette is made available under the terms of the Mozilla Public License 2.0, giving",
                    "you the freedom to use, copy, and distribute Vignette to others, in addition to",
                    "the right to distribute modified versions."
            ).forEach(System.out::println);
        }
        else if (options.has(mappingsSpec) && options.has(jarInSpec) && options.has(jarOutSpec)) {
            final Path jarInPath = options.valueOf(jarInSpec);
            final Path jarOutPath = options.valueOf(jarOutSpec);
            System.out.println("Input: " + jarInPath);
            System.out.println("Output: " + jarOutPath);
            if (Files.notExists(jarInPath)) {
                throw new RuntimeException("Input jar does not exist!");
            }

            final MappingFormat mappingFormat = options.valueOf(mappingFormatSpec);
            final Path mappingsPath = options.valueOf(mappingsSpec);
            if (Files.notExists(mappingsPath)) {
                throw new RuntimeException("Input mappings does not exist!");
            }

            final MappingSet mappings;
            try {
                System.out.println("Format: " + mappingFormat);
                System.out.println("Mappings: " + mappingsPath);
                mappings = mappingFormat.read(mappingsPath);
            }
            catch (final IOException ex) {
                throw new RuntimeException("Failed to read input mappings!", ex);
            }

            try (final Atlas atlas = createAtlas(options, threadsSpec)) {
                if (options.has(librarySpec)) {
                    for (final Path lib : options.valuesOf(librarySpec)) {
                        try {
                            atlas.use(lib);
                        }
                        catch (final IOException ex) {
                            throw new RuntimeException("Failed to read library!", ex);
                        }
                    }
                }

                atlas.install(ctx -> new EnhancedRemappingTransformer(mappings, ctx, options.has(ffmetaSpec)));
                if (options.has(ctrSpec)) {
                    atlas.install(ctx -> new ConstructorInjector(ctx, mappings));
                    System.out.println("Constructors");
                }

                atlas.run(jarInPath, jarOutPath);

                System.out.println("Processing Complete");
            }
            catch (final IOException ex) {
                throw new RuntimeException("Failed to remap artifact!", ex);
            }
        }
        else {
            try {
                parser.printHelpOn(System.err);
            }
            catch (final IOException ex) {
                System.err.println("Failed to print help information!");
                ex.printStackTrace(System.err);
            }
            System.exit(-1);
        }
    }

    private static String[] enhanceArgs(String[] args) {
        List<String> params = new ArrayList<>();
        for (int x = 0; x < args.length; x++) {
            if (args[x].startsWith("--cfg")) {
                String path = null;
                if (args[x].startsWith("--cfg="))
                    path = args[x].substring(5);
                else if (args.length > x+1)
                    path = args[++x];
                else
                    throw new IllegalArgumentException("Must specify a file when using --cfg argument.");
                Path file = Paths.get(path);

                if (!Files.exists(file))
                    throw new IllegalArgumentException("error: missing config '" + path + "'");

                try (Stream<String> stream = Files.lines(file)) {
                    stream.forEach(params::add);
                } catch (IOException e) {
                    throw new RuntimeException("error: Failed to read config file '" + path + "'", e);
                }
            } else
                params.add(args[x]);
        }
        return params.toArray(new String[params.size()]);
    }

    private static Atlas createAtlas(final OptionSet options, final OptionSpec<Integer> threadsSpec) {
        if (options.has(threadsSpec)) {
            return new Atlas(options.valueOf(threadsSpec));
        }

        return new Atlas();
    }

    private VignetteMain() {
    }

}
