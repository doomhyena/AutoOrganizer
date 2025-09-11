module com.example.autoorganizer {
    requires javafx.controls;
    requires javafx.graphics;
    requires javafx.base;
    requires org.controlsfx.controls;
    requires org.kordamp.bootstrapfx.core;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fontawesome5;
    requires net.synedra.validatorfx;
    requires com.google.gson;
    opens com.example.autoorganizer to com.google.gson, javafx.base;
    exports com.example.autoorganizer;
}