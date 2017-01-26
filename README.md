Mateusz Kowalczyk
Piotr Bukowski
Mateusz Chrobot
Jarek Krupa
#K0Torrent 
 Implementacja na podstawie protokołu Bittorrent

K0Torrent został zaimplementowany w języku Java z wykorzystaniem narzędzia Gardle na podstawie protokołu Bittorrent. Protokół ten służy do wymiany i dystrybucji plików przez Internet, którego celem jest odciążenie łączy serwera udostępniającego pliki. Jego największą zaletą w porównaniu do protokołu HTTP jest podział pasma pomiędzy osoby, które w tym samym czasie pobierają dany plik. Oznacza to, że użytkownik w czasie pobierania wysyła fragmenty pliku innym użytkownikom.

#1. Opis projektu
Projekt składa się z wielu pakietów przechowujące klasy o podobnym znaczeniu i przeznaczeniu. Poniżej zostanie przedstawiony dany pakiet wraz z klasami znajdującymi się  w nim. 

#1.1. Pakiet Bcodec oraz pakiet Bittorent
Meta plik jest zapisany w postaci zwykłego tekstu. Plik jest zakodowany, jest czymś w rodzaju pierścienia dekodującego, który pozwala zlokalizować różne części pliku, a następnie złożyć je w całość, kiedy już zostaną pobrane.System używany do kodowania plików torrent jest nazywany bencoding i analogicznie do kodowania bdecoder. Wewnątrz pakietu znajdują się cztery klasy. Klasy BDecoder/BEncoder obsługują zdarzenia odpowiednio związane z dekodowaniem/kodowaniem strumienia używanego przez system wymiany plików peer-to-peer protokołu BitTorrent do przechowywania i przesyłania danych. Klasa InvalidBEncodingException - służy do rzucenia wyjątkiem, gdy strumień nie może zostać zdekodowany. Klasa BeValue jest klasą pomocnicza obsługująca pozostałe klasy. W pakiecie Bittorent znajdują się klasy obsługjące klasy z pakietu Bcodec oraz Meta plik.
#1.2. Pakiet Cli
W pakiecie CLI znajdują się 4 klasy. Trzy z nich odpowiadają za obsługę interfejsu torrenta (ClientMain, TorrentMain, TrackerMain) oraz klasa CmdLineParser, jest to klasa pomocnicza, która została skopiowana z Jargs Api. Została używa w programie jako parser argumentów linii komend.

#1.3. Pakiet Tracker
Pakiet Tracker jak sama mówi służy do obsługi Trackera. Tracker to oprogramowanie instalowane po stronie serwera, które zarządza ruchem oraz informacjami jakie są wymieniane pomiędzy jego aktywnymi użytkownikami. Stosowany w sieci p2p BitTorrent, pozwala na dynamiczne udostępnianie całości lub części plików wielu użytkownikom a także umożliwia swobodne wymienianie się tymi danymi. Tracker również przechowuje wszystkie informacje dotyczące ruchu, fragmentów plików czy innych danych, które wielu serwisom internetowym potrzebne są do zarządzania swoją zawartością jak i użytkownikami.

#1.4. Pakiet Exceptions
Tutaj nie ma się co rozpisywać, jest to pakiet przechowujący klasy obsługujące wyjątki. Jeżeli w jakimś miejscu programu zajdzie nieoczekiwana sytuacja, programista piszący ten kod powinien zasygnalizować o tym. Czyli wyjątki to stany (zdarzenia wyjątkowe, w szczególności błędy), którego wystąpienie zmienia prawidłowy przebieg wykonywania się programu.

#1.5. Pakiet Common
Jest to pakiet do obsługi połączenia oraz obsługę protokołu UDP i HTTP. Znajduje się w nim klasa peer, która obsługuje model komunikacji w sieci komputerowej zapewniający wszystkim hostom te same uprawnienia, w odróżnieniu od architektury klient–serwer

#1.5.1 Pakiet protocol w pakiecie Common
Klasy PeerMessage oraz TrackerMessage służą do obsługi obsługi zdarzeń. Następnie w pakiecie UDP oraz HTTP znajdują się klasy obsługujące te protokoły. 
Protokół UDP  jest to protokół bezpołączeniowy, więc nie ma narzutu na nawiązywanie połączenia i śledzenie sesji (w przeciwieństwie do TCP). Nie ma też mechanizmów kontroli przepływu i retransmisji. Korzyścią płynącą z takiego uproszczenia budowy jest szybsza transmisja danych i brak dodatkowych zadań, którymi musi zajmować się host posługujący się tym protokołem. 
	Protokół HTTP w odniesieniu do BitTorrenta odpowiada na żądania HTTP GET. Pobiera dane od klientów, które pomagają utrzymać trackera, czyli statystyki dotyczące strumienia.

#1.6. Pakiet Client
Jest to najbardziej rozbudowany pakiet ze wszystkich. 

#1.7. Klasa Główna ClientMain.java
W klasie głównej zostają wykonywane wszystkie czynności obsługujące pobieranie oraz wysyłanie pliku .torrent.
