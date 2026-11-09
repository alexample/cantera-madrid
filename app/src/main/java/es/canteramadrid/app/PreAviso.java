package es.canteramadrid.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Salta unas horas antes de un partido de tus equipos y muestra dónde se juega. */
public class PreAviso extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        Avisos.mostrarPrevio(c, in);
    }
}
