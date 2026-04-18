package com.luoke;

import com.luoke.app.MapApp;
import javafx.application.Application;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Main {
    public static void main(String[] args) throws InterruptedException {
        Application.launch(MapApp.class, args);
    }
}