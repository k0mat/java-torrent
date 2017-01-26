package pl.uksw.edu.javatorrent;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import pl.uksw.edu.javatorrent.bittorrent.metadata.BittorrentMetaData;
import pl.uksw.edu.javatorrent.bittorrent.metadata.MetadataReader;
import pl.uksw.edu.javatorrent.exceptions.BencoderException;

import java.io.IOException;

/**
 * Created by mateusz on 2016-10-29.
 */
public class JavaTorrent {
    public static void main(String[] args) {
        Namespace ns = parseArgs(args);
        String torrentFilePath = ns.get("torrent");
        String outputDirectory = ns.get("output");
        MetadataReader reader = new MetadataReader();
        BittorrentMetaData metaData;

        try {
            metaData = reader.getMetaDataFromTorrentFile(torrentFilePath);
        } catch (IOException | BencoderException e) {
            e.printStackTrace();
            return;
        }

        System.out.println("name \t- " + metaData.getName());
        System.out.println("piece lenght  \t- " + metaData.getPieceLenght());
        System.out.println("trackers  \t- " + metaData.getTrackers());
        System.out.println("files  \t- " + metaData.getFiles());
        System.out.println("pieces  \t- " + metaData.getPieces());
    }

    private static Namespace parseArgs(String[] args) {
        ArgumentParser parser = ArgumentParsers.newArgumentParser("Java torrent")
                .defaultHelp(true)
                .description("Bittorrent client written in java");

        parser.addArgument("-t", "--torrent")
                .required(true)
                .help("Path to file containing bittorrent metadata.");

        parser.addArgument("-o", "--output")
                .setDefault(System.getProperty("user.home") + "/Downloads/")
                .help("Output directory");

        Namespace ns = null;
        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        }

        return ns;
    }
}
