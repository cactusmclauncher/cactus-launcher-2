package com.cactuslauncher.game;

public final class HorizonProcessFixture {
    public static final String API = "{\"launch_api\":\"0.1.0\",\"options\":[{\"name\":\"--resources\"},{\"name\":\"--game-dir\"},{\"name\":\"--home\"},{\"name\":\"--player-dir\"}]}";
    public static void main(String[] args) throws Exception {
        if (System.in.readAllBytes().length != 0) System.exit(42);
        if (args[0].equals("--launch-api")) { System.out.println(API); return; }
        if (args[0].equals("bad-api")) { System.out.println("{\"launch_api\":\"9\"}"); return; }
        System.out.println("access_token=fixture-sensitive-value");
        System.err.println("Horizon: controlled helper output");
        if (args[0].equals("wait")) Thread.sleep(60000);
        System.exit(args[0].equals("wait") ? 0 : Integer.parseInt(args[0]));
    }
}
