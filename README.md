# AutoOrganizer

🔥 **All-in-one fájlrendező / átnevező / backup / duplikátum-kereső app** JavaFX-ben.

Eleged van abból, hogy a letöltések mappádban több ezer fájl kóvályog összevissza?
A DoomSorter segít: kategorizál, átnevez, backup-ol és kigyomlálja a duplikátumokat – mindezt egy szép, modern GUI-ból.

---

## ✨ Funkciók

* **📂 Rendező**
  Fájlokat automatikusan kategóriákba pakol kiterjesztés alapján (pl. képek → `Pictures`, videók → `Videos`, stb.).
  Testreszabható JSON-ban: pl. `jpg → Fotók`, `pdf → Dokumentumok`.

* **🖼️ Renamer**

  * Dátum/idő alapján: `20230921_153000.jpg`
  * Prefix + sorszám: `Nyaralás_001.jpg`
    Hasznos főleg fotóknál / képeknél.

* **🔍 Duplikátum kereső**
  SHA-256 hash alapján megtalálja a felesleges másolatokat.
  Egy kattintással törölheted a kijelölteket.

* **💾 Backup (időzítve)**
  Beállítható forrás és cél mappa (pl. pendrive).
  Időzített napi másolatkészítés, vagy kézzel is indítható.

* **⚙️ Beállítások**
  Saját kategóriák hozzáadása / törlése.
  Wildcard `*` támogatás („minden más megy ide”).

---

## 🚀 Használat

### Követelmények

* **Java 17+**
* JavaFX + a következő libek:

  * [ControlsFX](https://github.com/controlsfx/controlsfx)
  * [Ikonli](https://kordamp.org/ikonli/)
  * [BootstrapFX](https://github.com/kordamp/bootstrapfx)
  * [Gson](https://github.com/google/gson)

### Indítás

```bash
git clone https://github.com/doomhyena/AutoOrganizer.git
cd doomsorter
javac -d out --module-path /path/to/javafx/lib --add-modules javafx.controls,javafx.fxml src/com/example/autoorganizer/*.java
java -cp out --module-path /path/to/javafx/lib --add-modules javafx.controls,javafx.fxml com.example.autoorganizer.Main
```

*(ha Gradle/Maven buildet is összeraksz, ide be lehet dobni a `./gradlew run` verziót is)*

---

## 📷 Képek (ajánlott)

* Rendező tab screenshot
* Duplikátum kereső screenshot
* Backup beállítás screenshot

*(Ezeket majd töltsd fel a `docs/screenshots` alá és linkeld ide.)*

---

## 🛠️ Technikai jegyzetek

* Beállítások JSON-ban mentődnek: `~/.AutoOrganizer/config.json`
* Minden hosszabb folyamat külön `Task`-ban fut, progress bar + status bar támogatással.
* Backup időzítést `ScheduledExecutorService` intézi.

---

## 📜 Licenc

MIT License
