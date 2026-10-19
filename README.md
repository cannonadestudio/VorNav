# VOR Nav — nawigacja VOR/DME na Androida

Aplikacja pomocnicza dla pilota VFR (samoloty i UL). Łączy:

- **Wirtualne odbiorniki NAV1 i NAV2 (VOR/DME).** Strojenie częstotliwością (np. `114.90`) albo identyfikatorem (np. `WAR`). Aplikacja pokazuje:
  - wskaźnik CDI z OBS i flagą TO/FROM,
  - radial QDR i kurs do stacji QDM,
  - odległość DME (skośną, z uwzględnieniem wysokości), prędkość względem stacji i czas do stacji.
  - Działa flaga OFF poza zasięgiem radiowym, liczonym z horyzontu i klasy mocy stacji.
- **Odbiornik ADF (NDB).** Strojenie częstotliwością w kHz (np. `375`) albo identyfikatorem. Wskaźnik RMI/ADF pokazuje:
  - kąt kursowy radiostacji (RB),
  - QDM i QDR,
  - odległość (z DME dla NDB-DME) i czas do stacji.
  - Najdokładniej działa z kursem HDG wpisanym z żyrobusoli. Bez niego używa TRK z GPS, który przy wietrze różni się o kąt znoszenia.
  - Fix z przecięcia namiaru NDB i radialu VOR, linia namiaru NDB na mapie.
- **Trasa od–do po radialach.** Wybierasz lotnisko startu i celu, a przycisk „Wyznacz przez VOR” buduje najkrótszą trasę, w której każdy odcinek zaczyna się lub kończy na VOR.
  - Każdy odcinek dostaje kurs, odległość, czas, stację, tryb TO/FROM i OBS oraz punkt kontrolny (radial/DME lub przecięcie radiali).
  - Możesz dodawać, przesuwać i usuwać punkty pośrednie.
  - Po „Start” NAV1 stroi się sam na stację odcinka, D→ wskazuje koniec odcinka, widać XTK, a odcinki przełączają się automatycznie.
- **Aktywne strefy z AUP/UUP (PAŻP).** Strefy pobierane z airspace.pansa.pl przy starcie i co 30 minut, zapisywane do użytku offline.
  - Na mapie: czerwone = aktywne teraz, pomarańczowe przerywane = aktywne później w ciągu 12 h, z oznaczeniem i wysokościami. Przycisk warstw włącza i wyłącza strefy.
  - Ostrzeżenie w zakładce NAV i na mapie, gdy jesteś w aktywnej strefie albo lecisz na nią w ciągu 5 NM, z uwzględnieniem wysokości.
  - Sprawdzanie trasy: lista stref przecinanych przez trasę na planowanej wysokości.
  - Lista stref w zakładce Baza, z filtrem dolnej granicy (domyślnie poniżej 9500 ft).
- **Pełna przestrzeń powietrzna Polski i Europy (openAIP).** CTR/MCTR, TMA/CTA, ATZ, TRA/TSA, R/P/D, RMZ/TMZ, MRT, FIR i inne, pobierane per kraj (presety: Polska, + sąsiedzi, Europa).
  - Każdą grupę włączasz i wyłączasz osobno w zakładce Baza.
  - Dotknięcie pustego miejsca na mapie pokazuje wszystkie strefy w tym punkcie (statyczne i AUP).
  - W zakładce NAV linia „Przestrzeń w Twojej pozycji”.
  - Dane openAIP, licencja CC BY-NC 4.0 (użycie niekomercyjne, wymagane podanie źródła).
- **Małe przyrządy na mapie** (przycisk z prędkościomierzem): NAV1/NAV2 z paskiem CDI, TO/FROM, OBS (−/+, dotknięcie OBS centruje na TO), QDM i DME oraz ADF z mini-RMI, QDM i odległością.
- **Ostrzeżenia w locie z dźwiękiem i wibracją:**
  - aktywne strefy AUP/UUP,
  - strefy P (czerwone) oraz R i D z AIP (pomarańczowe, z przypomnieniem o sprawdzeniu aktywności),
  - informacja o zbliżaniu się do granicy CTR/TMA/ATZ/RMZ.
  - Wszystko w zasięgu 5 NM przed samolotem i z uwzględnieniem wysokości.
- **Trening IFR (zakładka Trening):**
  - **Samolot symulacji:** kurs z zakrętem standardowym 3°/s, TAS, prędkość pionowa, wiatr ze znoszeniem. Sterowanie także na mapie.
  - **Trener holdingu:** losowy holding nad VOR/NDB, prawy/lewy, z wiatrem.
    - Pytania o wejście (direct/parallel/teardrop wg ICAO), kurs i czas odlotu z oceną.
    - Holding rysowany na mapie, pomiar czasu dolotu i zejścia z osi w każdym okrążeniu.
  - **Trener przechwytywania:** radial TO/FROM albo QDM/QDR NDB. Sprawdza podany kurs przechwycenia, a w locie mierzy czas do ustabilizowania i przestrzał.
  - **ILS (syntetyczny):** z danych pasa (oś pasa, ścieżka 3°).
    - Działa na żywo z GPS w zakładce NAV i na mapie oraz w trybie treningu (start 10 NM przed pasem).
    - Pokazuje LOC/GS, odległość do progu, wysokość na ścieżce i VS.
  - **Zapis lotu:** ślad na mapie, zapis GPX, eksport do pliku. Treningi nagrywają się automatycznie.
- **Direct-To** do dowolnego lotniska, lądowiska lub pomocy nawigacyjnej. Pokazuje BRG, DIST, ETE, ETA i strzałkę skrętu.
- **Bazę lotnisk i pomocy nawigacyjnych** pobieraną z OurAirports, filtrowaną po krajach i działającą offline. Do tego **własne lądowiska** (UL, trawiaste) dodawane ręcznie albo z pliku CSV.
- **Mapę** (OpenStreetMap / osmdroid). Pokazuje VOR, DME, NDB, lotniska, samolot, linię D→ i linie kursów OBS z NAV1/NAV2. Dotknięcie punktu otwiera akcje.
- **Pozycję z GPS z korektą:**
  - fix z odczytu radial + DME z prawdziwego przyrządu w samolocie,
  - fix z przecięcia dwóch radiali,
  - „Jestem nad” punktem,
  - ręczne współrzędne.
  - Korekta działa jako przesunięcie dodawane do kolejnych odczytów GPS. Przy utracie GPS aplikacja przechodzi na zliczanie drogi (DR).
- **Wysokość z wysokościomierza** do dokładniejszego DME oraz **tryb symulacji** do ćwiczeń na ziemi.

## Ważne: jak działa „VOR/DME” w telefonie

Telefon nie ma odbiornika VHF NAV 108–118 MHz, więc **nie odbiera sygnału VOR**. Nie może też pracować z DME, bo DME to układ zapytanie–odpowiedź, który wymaga nadajnika w samolocie.

Wskazania są więc **liczone** z pozycji GPS i współrzędnych stacji z bazy. Ta sama geometria daje te same liczby, co prawdziwy przyrząd: radial, DME, odchylenie CDI, TO/FROM.

Radiale są liczone względem deklinacji, do której skalibrowano stację (`slaved variation` z bazy). Gdy jej brak, aplikacja używa bieżącej deklinacji z modelu WMM wbudowanego w Androida. Przełącznik jest w zakładce Korekta.

Połączenie z prawdziwym odbiornikiem odbywa się przez **korektę pozycji**: wpisujesz radial i DME odczytane z przyrządu w samolocie, a aplikacja przesuwa pozycję GPS.

Jeżeli kiedyś zechcesz czytać prawdziwy odbiornik automatycznie, potrzebny jest sprzęt z wyjściem danych, np. NAV/COM z wyjściem RS-232/ARINC 429 i konwerterem na Bluetooth, albo odbiornik SDR (RTL-SDR przez USB-OTG) z demodulacją VOR. To jest kolejny etap projektu.

## Budowanie

1. Zainstaluj **Android Studio** (Ladybug lub nowsze).
2. *File → Open* → wskaż folder `VorNav`. Studio samo pobierze Gradle 8.14 i zależności.
   - Jeśli zapyta o brak `gradlew`, zgódź się na utworzenie wrappera albo uruchom `gradle wrapper`.
3. Podłącz telefon z włączonym debugowaniem USB → **Run ▶**.
   - Wersja do instalacji: *Build → Build APK(s)*. Na Google Play: *Build → Generate Signed App Bundle*.

Wymagania: Android 8.0+ (API 26), GPS.

## Pierwsze uruchomienie

1. Zezwól na lokalizację.
2. Zakładka **Baza**: wpisz kraje (domyślnie `PL,DE,CZ,SK,LT`) → **Pobierz bazę**. Pobranie wymaga internetu i trwa ok. 1 minuty. Potem baza działa offline.
3. Zakładka **NAV** → **Strój** → wpisz `114.90` lub ident stacji. Kręć OBS przyciskami ±1/±10. **CDI→TO** centruje wskazówkę na kurs do stacji.
4. Lądowiska UL: **Baza → Dodaj lądowisko** albo **Importuj plik CSV** (wzór w `przyklad_ladowiska.csv`):
   ```
   IDENT;Nazwa;szerokość;długość;elewacja_ft
   EPXY;Lądowisko;52.1234;20.5678;350
   EPXY;Lądowisko;N52°07'24";E020°34'04";350
   ```
5. Do ćwiczeń w domu: **Korekta → Symulacja**. Ustaw GS i TRK, a samolot poleci wirtualnie.

## Struktura kodu

| Plik | Co robi |
|---|---|
| `nav/Geo.kt` | odległości, kursy, punkt docelowy, przecięcie radiali, deklinacja (WMM) |
| `nav/AdfReceiver.kt` | wirtualny ADF: QDM/QDR, kąt kursowy, zasięg NDB |
| `nav/RoutePlanner.kt` | trasa przez VOR (Dijkstra), instrukcje radialowe odcinków, XTK |
| `data/Airspace.kt` | pobieranie i parsowanie AUP/UUP z airspace.pansa.pl, geometria stref |
| `nav/ZoneAlerts.kt` | ostrzeżenia o strefach i konflikty trasy ze strefami |
| `data/StaticAirspace.kt` | przestrzeń z openAIP: pobieranie krajów, strumieniowe czytanie GeoJSON, grupy stref |
| `nav/Trainers.kt` | wejścia w holding, WCA, kształt holdingu, syntetyczny ILS |
| `ui/TrainerController.kt` | logika trenerów holdingu i przechwytywania |
| `nav/VorReceiver.kt` | wirtualny odbiornik: radial, CDI, TO/FROM, DME skośne, zasięg, fixy |
| `data/NavDb.kt` | baza SQLite: wyszukiwanie, najbliższe, częstotliwości, własne lądowiska |
| `data/Importer.kt` | import OurAirports (CSV RFC 4180) i własnych CSV, parser współrzędnych DMS |
| `loc/PositionEngine.kt` | GPS, korekta pozycji, DR przy utracie GPS, symulacja |
| `ui/*` | ekrany Compose: Mapa, NAV, Szukaj, Korekta, Baza; wskaźnik CDI |

## Przed publikacją w Google Play

- Kafelki OSM: polityka OpenStreetMap nie pozwala na intensywne użycie ich serwera w aplikacjach publicznych. Na produkcję podepnij własny serwer kafelków lub dostawcę (np. MapTiler). Mapy lotnicze VFR są dostępne np. z openflightmaps.org (sprawdź licencję).
- Dodaj ekran z zastrzeżeniem, że aplikacja nie jest certyfikowanym urządzeniem nawigacyjnym.
- Dane OurAirports bywają niekompletne. W Polsce część NDB jest już wycofana, a baza może je wciąż zawierać. Lądowiska UL i częstotliwości weryfikuj w AIP Polska i VFR Polska.

**Zastrzeżenie:** aplikacja ma charakter pomocniczy i nie zastępuje certyfikowanych przyrządów, aktualnych map ani AIP.
