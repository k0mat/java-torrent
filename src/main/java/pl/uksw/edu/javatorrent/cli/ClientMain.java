package pl.uksw.edu.javatorrent.cli;

import pl.uksw.edu.javatorrent.client.Client;
import pl.uksw.edu.javatorrent.client.SharedTorrent;

import java.io.File;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Enumeration;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientMain {

    private static final Logger logger =
            LoggerFactory.getLogger(ClientMain.class);

    private static final String DEFAULT_OUTPUT_DIRECTORY = "/tmp";
    private static Inet4Address getIPv4Address(String iface)
            throws SocketException, UnsupportedAddressTypeException,
            UnknownHostException {
        if (iface != null) {
            Enumeration<InetAddress> addresses =
                    NetworkInterface.getByName(iface).getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address) {
                    return (Inet4Address)addr;
                }
            }
        }

        InetAddress localhost = InetAddress.getLocalHost();
        if (localhost instanceof Inet4Address) {
            return (Inet4Address)localhost;
        }

        throw new UnsupportedAddressTypeException();
    }

    private static void usage(PrintStream s) {
        s.println("usage: Client [options] <torrent>");
        s.println();
        s.println("Available options:");
        s.println("  -h,--help                  Show this help and exit.");
        s.println("  -o,--output DIR            Read/write data to directory DIR.");
        s.println("  -i,--iface IFACE           Bind to interface IFACE.");
        s.println("  -s,--seed SECONDS          Time to seed after downloading (default: infinitely).");
        s.println("  -d,--max-download KB/SEC   Max download rate (default: unlimited).");
        s.println("  -u,--max-upload KB/SEC     Max upload rate (default: unlimited).");
        s.println();
    }

    public static void main(String[] args) {
        BasicConfigurator.configure(new ConsoleAppender(
                new PatternLayout("%d [%-25t] %-5p: %m%n")));

        CmdLineParser parser = new CmdLineParser();
        CmdLineParser.Option help = parser.addBooleanOption('h', "help");
        CmdLineParser.Option output = parser.addStringOption('o', "output");
        CmdLineParser.Option iface = parser.addStringOption('i', "iface");
        CmdLineParser.Option seedTime = parser.addIntegerOption('s', "seed");
        CmdLineParser.Option maxUpload = parser.addDoubleOption('u', "max-upload");
        CmdLineParser.Option maxDownload = parser.addDoubleOption('d', "max-download");

        try {
            parser.parse(args);
        } catch (CmdLineParser.OptionException oe) {
            System.err.println(oe.getMessage());
            usage(System.err);
            System.exit(1);
        }
        if (Boolean.TRUE.equals((Boolean)parser.getOptionValue(help))) {
            usage(System.out);
            System.exit(0);
        }

        String outputValue = (String)parser.getOptionValue(output,
                DEFAULT_OUTPUT_DIRECTORY);
        String ifaceValue = (String)parser.getOptionValue(iface);
        int seedTimeValue = (Integer)parser.getOptionValue(seedTime, -1);

        double maxDownloadRate = (Double)parser.getOptionValue(maxDownload, 0.0);
        double maxUploadRate = (Double)parser.getOptionValue(maxUpload, 0.0);

        String[] otherArgs = parser.getRemainingArgs();
        if (otherArgs.length != 1) {
            usage(System.err);
            System.exit(1);
        }

        try {
            Client c = new Client(
                    getIPv4Address(ifaceValue),
                    SharedTorrent.fromFile(
                            new File(otherArgs[0]),
                            new File(outputValue)));

            c.setMaxDownloadRate(maxDownloadRate);
            c.setMaxUploadRate(maxUploadRate);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(new Client.ClientShutdown(c, null)));

            c.share(seedTimeValue);
            if (Client.ClientState.ERROR.equals(c.getState())) {
                System.exit(1);
            }
        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(2);
        }
    }
}
