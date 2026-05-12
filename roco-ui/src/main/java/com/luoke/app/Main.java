package com.luoke.app;

import com.luoke.app.ui.ModernCanvasApp;
import com.luoke.app.utils.FileUtil;
import javafx.application.Application;

public class Main {

    static void main(String[] args) {
        FileUtil.extractAll();
        Application.launch(ModernCanvasApp.class, args);
    }
}
