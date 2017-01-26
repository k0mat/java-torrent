package pl.uksw.edu.javatorrent.bittorrent.metadata;


import pl.uksw.edu.javatorrent.bittorrent.bencoder.BencoderMapper;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.BencoderParser;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderDictionary;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderObject;
import pl.uksw.edu.javatorrent.exceptions.BencoderException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MetadataReader {
    private BencoderMapper mapper;
    private BencoderParser parser;

    public MetadataReader() {
        mapper = new BencoderMapper();
        parser = new BencoderParser();
    }

    private static String readFile(String path, Charset encoding)
            throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }

    public BittorrentMetaData getMetaDataFromTorrentFile(String path) throws IOException, BencoderException {
        String file = readFile(path, StandardCharsets.US_ASCII);
        BencoderObject parsed = parser.parseFirst(file);
        if (!(parsed instanceof BencoderDictionary)) {
            throw new BencoderException("Invalid file data");
        }
        return BittorrentMetaData.fromDictionary(((BencoderDictionary) parsed).getValue());
    }
}
