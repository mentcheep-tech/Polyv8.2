package com.polymarket.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;

public class ClobAuthManager {
    public interface Callback { void done(boolean ok, String message); }
    private static final String PREF="clob_auth";
    private static final String KEY="blob";
    private static final String KS="AndroidKeyStore";
    private static final String ALIAS="PolymarketClientCLOB";
    private static final String CLOB="https://clob.polymarket.com";
    private static final String ZERO="0x0000000000000000000000000000000000000000000000000000000000000000";
    private static final String EXCHANGE_V2="0xE111180000d2663C0091e4f400237545B87B996B";
    private static final String NEG_RISK_EXCHANGE_V2="0xe2222d279d744050d28e00520010520000310F59";
    private final Context ctx;
    private final SharedPreferences prefs;
    private String address="";
    private String apiKey="", secret="", passphrase="", privateKey="";

    public ClobAuthManager(Context c){ctx=c.getApplicationContext();prefs=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);loadCreds();}
    public String getAddress(){return address;}
    public boolean hasCreds(){return !apiKey.isEmpty()&&!secret.isEmpty()&&!passphrase.isEmpty()&&!address.isEmpty();}
    public boolean hasPrivateKey(){return !privateKey.isEmpty();}
    public String getApiKey(){return apiKey;}
    public String getPassphrase(){return passphrase;}
    public String getSecretMasked(){return secret.isEmpty()?"":secret.substring(0, Math.min(6, secret.length()))+"…";}
    public void saveManualCredentials(String addr,String key,String sec,String pass){address=addr==null?"":addr.trim();apiKey=key==null?"":key.trim();secret=sec==null?"":sec.trim();passphrase=pass==null?"":pass.trim();saveCreds();}
    public void updateManualCredentials(String addr,String key,String sec,String pass){if(addr!=null&&!addr.trim().isEmpty())address=addr.trim();if(key!=null&&!key.trim().isEmpty())apiKey=key.trim();if(sec!=null&&!sec.trim().isEmpty())secret=sec.trim();if(pass!=null&&!pass.trim().isEmpty())passphrase=pass.trim();saveCreds();}

    public void authenticate(String privateKeyHex, long nonce, Callback cb){
        new Thread(()->{
            try{
                String pk=normalizePrivateKey(privateKeyHex);
                ECKeyPair kp=ECKeyPair.create(Numeric.hexStringToByteArray("0x"+pk));
                Credentials credentials=Credentials.create(kp);
                String addr=credentials.getAddress();
                long ts=System.currentTimeMillis()/1000L;
                String typed=clobAuthTypedData(addr,ts,nonce);
                byte[] digest=new StructuredDataEncoder(typed).hashStructuredData();
                String l1=signDigest(digest,kp);
                OkHttpClientHolder.postAuth(addr,l1,ts,nonce,(ok,msg)->{
                    if(!ok){cb.done(false,msg);return;}
                    try{
                        JSONObject o=new JSONObject(msg);
                        String k=o.optString("apiKey",o.optString("api_key"));
                        String sec=o.optString("secret");
                        String pass=o.optString("passphrase");
                        if(k.isEmpty()||sec.isEmpty()||pass.isEmpty()) throw new IllegalStateException("CLOB did not return complete L2 credentials: "+msg);
                        address=addr;apiKey=k;secret=sec;passphrase=pass;privateKey=pk;saveCreds();
                        cb.done(true,"CLOB L1/L2 authentication successful\nSigner: "+addr+"\nAPI key: "+shortKey(k)+"\nPrivate key: encrypted on device");
                    }catch(Exception e){cb.done(false,"Credential parse error: "+e.getMessage());}
                });
            }catch(Exception e){cb.done(false,"Authentication error: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        }).start();
    }

    public void testAuthenticated(Callback cb){
        if(!hasCreds()){cb.done(false,"No stored CLOB credentials. Authenticate first.");return;}
        new Thread(()->{
            try{
                long ts=System.currentTimeMillis()/1000L;
                String path="/data/orders";
                String sig=l2Signature(ts,"GET",path,"");
                okhttp3.Request r=new okhttp3.Request.Builder().url(CLOB+path)
                        .addHeader("POLY_ADDRESS",address).addHeader("POLY_API_KEY",apiKey)
                        .addHeader("POLY_PASSPHRASE",passphrase).addHeader("POLY_SIGNATURE",sig)
                        .addHeader("POLY_TIMESTAMP",String.valueOf(ts)).get().build();
                okhttp3.OkHttpClient client=new okhttp3.OkHttpClient.Builder().build();
                try(okhttp3.Response resp=client.newCall(r).execute()){
                    String body=resp.body()!=null?resp.body().string():"";
                    cb.done(resp.isSuccessful(),"Authenticated CLOB GET /data/orders HTTP "+resp.code()+"\n"+body);
                }
            }catch(Exception e){cb.done(false,"Authenticated request error: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        }).start();
    }

    /** Places a V2 FOK market BUY using the current book price supplied by the caller. */
    public void placeLiveMarketBuy(String tokenId, double usdAmount, double executionPrice, double tickSize, boolean negRisk, Callback cb){
        placeLiveMarketOrder(tokenId,usdAmount,executionPrice,tickSize,negRisk,"BUY","FOK",cb);
    }

    public void placeLiveMarketOrder(String tokenId,double amount,double executionPrice,double tickSize,boolean negRisk,String side,String orderType,Callback cb){
        new Thread(()->{
            try{
                if(!hasCreds()) throw new IllegalStateException("CLOB L2 credentials are missing");
                if(!hasPrivateKey()) throw new IllegalStateException("Wallet private key is not stored. Authenticate in CLOB KEYS first.");
                if(tokenId==null||tokenId.isEmpty()) throw new IllegalArgumentException("Missing token ID");
                if(amount<=0||executionPrice<=0) throw new IllegalArgumentException("Invalid amount or execution price");
                if(!"BUY".equals(side)) throw new IllegalArgumentException("This build only enables live BUY execution");
                String pk=privateKey;
                ECKeyPair kp=ECKeyPair.create(Numeric.hexStringToByteArray("0x"+pk));
                String signer=Credentials.create(kp).getAddress();
                if(address!=null&&!address.isEmpty()&&!address.equalsIgnoreCase(signer)) throw new IllegalStateException("Stored signer does not match CLOB address");

                BigDecimal tick=new BigDecimal(tickSize<=0?"0.01":String.valueOf(tickSize));
                BigDecimal price=new BigDecimal(String.valueOf(executionPrice)).divide(tick,0,RoundingMode.FLOOR).multiply(tick);
                BigDecimal maker=new BigDecimal(String.valueOf(amount)).setScale(2,RoundingMode.DOWN);
                BigDecimal taker=maker.divide(price,8,RoundingMode.UP).setScale(4,RoundingMode.DOWN);
                BigInteger makerUnits=maker.movePointRight(6).setScale(0,RoundingMode.DOWN).toBigIntegerExact();
                BigInteger takerUnits=taker.movePointRight(6).setScale(0,RoundingMode.DOWN).toBigIntegerExact();
                if(makerUnits.signum()<=0||takerUnits.signum()<=0) throw new IllegalStateException("Order rounded to zero");

                long timestamp=System.currentTimeMillis();
                BigInteger salt=new BigInteger(62,new SecureRandom());
                String exchange=negRisk?NEG_RISK_EXCHANGE_V2:EXCHANGE_V2;
                JSONObject typed=new JSONObject();
                JSONObject domain=new JSONObject().put("name","Polymarket CTF Exchange").put("version","2").put("chainId",137).put("verifyingContract",exchange);
                JSONObject types=new JSONObject();
                JSONArray orderTypes=new JSONArray();
                orderTypes.put(new JSONObject().put("name","salt").put("type","uint256"));
                orderTypes.put(new JSONObject().put("name","maker").put("type","address"));
                orderTypes.put(new JSONObject().put("name","signer").put("type","address"));
                orderTypes.put(new JSONObject().put("name","tokenId").put("type","uint256"));
                orderTypes.put(new JSONObject().put("name","makerAmount").put("type","uint256"));
                orderTypes.put(new JSONObject().put("name","takerAmount").put("type","uint256"));
                orderTypes.put(new JSONObject().put("name","side").put("type","uint8"));
                orderTypes.put(new JSONObject().put("name","signatureType").put("type","uint8"));
                orderTypes.put(new JSONObject().put("name","timestamp").put("type","uint256"));
                orderTypes.put(new JSONObject().put("name","metadata").put("type","bytes32"));
                orderTypes.put(new JSONObject().put("name","builder").put("type","bytes32"));
                types.put("Order",orderTypes);
                JSONObject message=new JSONObject().put("salt",salt.toString()).put("maker",signer).put("signer",signer)
                        .put("tokenId",new BigInteger(tokenId).toString()).put("makerAmount",makerUnits.toString()).put("takerAmount",takerUnits.toString())
                        .put("side",0).put("signatureType",0).put("timestamp",String.valueOf(timestamp)).put("metadata",ZERO).put("builder",ZERO);
                typed.put("types",types).put("domain",domain).put("primaryType","Order").put("message",message);
                byte[] digest=new StructuredDataEncoder(typed.toString()).hashStructuredData();
                String signature=signDigest(digest,kp);

                JSONObject order=new JSONObject().put("maker",signer).put("signer",signer).put("tokenId",tokenId)
                        .put("makerAmount",makerUnits.toString()).put("takerAmount",takerUnits.toString()).put("side",side)
                        .put("expiration","0").put("timestamp",String.valueOf(timestamp)).put("metadata",ZERO).put("builder",ZERO)
                        .put("signature",signature).put("salt",salt.toString()).put("signatureType",0);
                JSONObject body=new JSONObject().put("order",order).put("owner",apiKey).put("orderType",orderType).put("deferExec",false);
                postSignedOrder(body.toString(),cb);
            }catch(Exception e){cb.done(false,"Live order error: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        }).start();
    }

    private void postSignedOrder(String body,Callback cb){
        try{
            long ts=System.currentTimeMillis()/1000L;
            String path="/order";
            String sig=l2Signature(ts,"POST",path,body);
            okhttp3.Request r=new okhttp3.Request.Builder().url(CLOB+path)
                    .addHeader("Content-Type","application/json")
                    .addHeader("POLY_ADDRESS",address).addHeader("POLY_API_KEY",apiKey)
                    .addHeader("POLY_PASSPHRASE",passphrase).addHeader("POLY_SIGNATURE",sig)
                    .addHeader("POLY_TIMESTAMP",String.valueOf(ts))
                    .post(okhttp3.RequestBody.create(body,okhttp3.MediaType.parse("application/json"))).build();
            okhttp3.OkHttpClient client=new okhttp3.OkHttpClient.Builder().build();
            client.newCall(r).enqueue(new okhttp3.Callback(){
                public void onFailure(okhttp3.Call c,java.io.IOException e){cb.done(false,"POST /order network error: "+e.getClass().getSimpleName()+": "+e.getMessage());}
                public void onResponse(okhttp3.Call c,okhttp3.Response resp)throws java.io.IOException{String b=resp.body()!=null?resp.body().string():"";cb.done(resp.isSuccessful(),"POST /order HTTP "+resp.code()+"\n"+b);}
            });
        }catch(Exception e){cb.done(false,"POST /order signing error: "+e.getMessage());}
    }

    public String l2Signature(long ts,String method,String path,String body) throws Exception{
        String message=String.valueOf(ts)+method.toUpperCase(Locale.US)+path+body;
        byte[] secretBytes=Base64.decode(secret,Base64.DEFAULT);
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secretBytes,"HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }

    private String clobAuthTypedData(String addr,long ts,long nonce){
        try{
            JSONObject root=new JSONObject();
            JSONObject domain=new JSONObject().put("name","ClobAuthDomain").put("version","1").put("chainId",137);root.put("domain",domain);
            JSONObject types=new JSONObject(); JSONArray arr=new JSONArray();
            arr.put(new JSONObject().put("name","address").put("type","address"));
            arr.put(new JSONObject().put("name","timestamp").put("type","string"));
            arr.put(new JSONObject().put("name","nonce").put("type","uint256"));
            arr.put(new JSONObject().put("name","message").put("type","string"));
            types.put("ClobAuth",arr);root.put("types",types).put("primaryType","ClobAuth");
            root.put("message",new JSONObject().put("address",addr).put("timestamp",String.valueOf(ts)).put("nonce",nonce).put("message","This message attests that I control the given wallet"));
            return root.toString();
        }catch(Exception e){throw new RuntimeException(e);}
    }

    private String normalizePrivateKey(String value){
        String pk=value.trim();if(pk.startsWith("0x")||pk.startsWith("0X"))pk=pk.substring(2);if(pk.length()!=64||!pk.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("Private key must be 64 hex characters");return pk.toLowerCase(Locale.US);
    }
    private String signDigest(byte[] digest,ECKeyPair kp){
        Sign.SignatureData sig=Sign.signMessage(digest,kp,false);byte[] sigBytes=new byte[65];System.arraycopy(sig.getR(),0,sigBytes,0,32);System.arraycopy(sig.getS(),0,sigBytes,32,32);sigBytes[64]=sig.getV()[0];return Numeric.toHexString(sigBytes);
    }

    private void saveCreds(){
        try{JSONObject o=new JSONObject().put("address",address).put("apiKey",apiKey).put("secret",secret).put("passphrase",passphrase).put("privateKey",privateKey);prefs.edit().putString(KEY,encrypt(o.toString())).apply();}catch(Exception ignored){}
    }
    private void loadCreds(){
        try{String s=prefs.getString(KEY,"");if(s.isEmpty())return;JSONObject o=new JSONObject(decrypt(s));address=o.optString("address");apiKey=o.optString("apiKey");secret=o.optString("secret");passphrase=o.optString("passphrase");privateKey=o.optString("privateKey");}catch(Exception ignored){address=apiKey=secret=passphrase=privateKey="";}
    }
    public void clear(){prefs.edit().remove(KEY).apply();address=apiKey=secret=passphrase=privateKey="";}
    private SecretKey key() throws Exception{
        KeyStore ks=KeyStore.getInstance(KS);ks.load(null);
        if(!ks.containsAlias(ALIAS)){
            javax.crypto.KeyGenerator kg=javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,KS);
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
    }
    private String encrypt(String plain)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());byte[] out=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));byte[] all=new byte[c.getIV().length+out.length];System.arraycopy(c.getIV(),0,all,0,c.getIV().length);System.arraycopy(out,0,all,c.getIV().length,out.length);return Base64.encodeToString(all,Base64.NO_WRAP);}
    private String decrypt(String enc)throws Exception{byte[] all=Base64.decode(enc,Base64.DEFAULT);byte[] iv=new byte[12];byte[] ct=new byte[all.length-12];System.arraycopy(all,0,iv,0,12);System.arraycopy(all,12,ct,0,ct.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new javax.crypto.spec.GCMParameterSpec(128,iv));return new String(c.doFinal(ct),StandardCharsets.UTF_8);}
    private String shortKey(String s){return s.length()<12?s:s.substring(0,8)+"…"+s.substring(s.length()-4);}

    static class OkHttpClientHolder{
        static void postAuth(String address,String signature,long ts,long nonce,ClobAuthManager.Callback cb){
            okhttp3.OkHttpClient client=new okhttp3.OkHttpClient.Builder().build();
            okhttp3.Request r=new okhttp3.Request.Builder().url(CLOB+"/auth/api-key")
                    .addHeader("POLY_ADDRESS",address).addHeader("POLY_SIGNATURE",signature).addHeader("POLY_TIMESTAMP",String.valueOf(ts)).addHeader("POLY_NONCE",String.valueOf(nonce)).post(okhttp3.RequestBody.create(new byte[0],null)).build();
            client.newCall(r).enqueue(new okhttp3.Callback(){public void onFailure(okhttp3.Call c,java.io.IOException e){cb.done(false,"L2 credential request failed: "+e.getClass().getSimpleName()+": "+e.getMessage());}public void onResponse(okhttp3.Call c,okhttp3.Response r)throws java.io.IOException{String b=r.body()!=null?r.body().string():"";cb.done(r.isSuccessful(),"HTTP "+r.code()+" "+b);}});
        }
    }
}
