package com.casioeurope.mis.covcertcheck;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;

import com.casioeurope.mis.covcertcheck.databinding.ActivityMainBinding;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding activityMainBinding;
    private boolean isScanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        setContentView(R.layout.activity_main);
        activityMainBinding = ActivityMainBinding.inflate(getLayoutInflater());
        View view = activityMainBinding.getRoot();
        setContentView(view);

        activityMainBinding.textViewBarcodeData.setMovementMethod(new ScrollingMovementMethod());
        activityMainBinding.buttonScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doScan();
            }
        });
        doScan();
    }

    private void doScan() {
        if (isScanning) return;
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES);
        integrator.setPrompt("Please Scan your Vaccination Certificate Barcode!");
        integrator.setCameraId(0);  // Use a specific camera of the device
        integrator.setBeepEnabled(false);
        integrator.setBarcodeImageEnabled(true);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    // Get the results:
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if(result != null) {
            if(result.getContents() == null) {
                setText("Scan Cancelled.");
            } else {
                setText(decodeCert(result.getContents()));
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void setText(String text) {
        text = text.replace("\n", "<br/>");
        text = text.replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;");
        text = text.replace("  ", "&nbsp;&nbsp;");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            activityMainBinding.textViewBarcodeData.setText(Html.fromHtml(text,Html.FROM_HTML_MODE_LEGACY));
        } else {
            activityMainBinding.textViewBarcodeData.setText(Html.fromHtml(text));
        }
    }

    private String decodeCert(String encodedCert) {
        String retVal = "Not a valid Vaccination Certificate!";
        if (!encodedCert.startsWith("HC1:")) return retVal;
        byte[] base45DecodedCert = Base45.getDecoder().decode(encodedCert.substring(4));
        Inflater decompresser = new Inflater();
        decompresser.setInput(base45DecodedCert, 0, base45DecodedCert.length);
        byte[] result = new byte[4096];
        try {
            int resultLength = decompresser.inflate(result);
        } catch (DataFormatException e) {
            e.printStackTrace();
            return retVal;
        } finally {
            decompresser.end();
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(result);
        List<DataItem> dataItems = null;
        try {
            dataItems = new CborDecoder(bais).decode();
        } catch (CborException e) {
            e.printStackTrace();
            return retVal;
        }
        retVal = "";
        for(DataItem dataItem : dataItems) {
            System.out.println(dataItem.toString());
            if (dataItem.getMajorType() == MajorType.ARRAY) {
                co.nstant.in.cbor.model.Array a = (co.nstant.in.cbor.model.Array)dataItem;
                List<DataItem>arrayDataItems = a.getDataItems();
                if (arrayDataItems.size() > 2) {
                    DataItem item = arrayDataItems.get(2);
                    if (item.getMajorType() == MajorType.BYTE_STRING) {
                        ByteString bs = (ByteString) item;
                        ByteArrayInputStream bais2 = new ByteArrayInputStream(bs.getBytes());
                        List<DataItem> dataItems2 = null;
                        try {
                            dataItems2 = new CborDecoder(bais2).decode();
                        } catch (CborException e) {
                            e.printStackTrace();
                            return retVal;
                        }
                        if (dataItems2.size() > 0) {
                            DataItem item2 = dataItems2.get(0);
                            if (item2.getMajorType() == MajorType.MAP) {
                                co.nstant.in.cbor.model.Map itemMap2 = (co.nstant.in.cbor.model.Map)item2;
                                String mapString = mapToString(itemMap2);
                                try {
                                    JSONObject jsonObject = new JSONObject(mapString);
                                    StringBuilder sb = new StringBuilder();
                                    sb.append(translate(jsonObject));
                                    sb.append("\n\nRaw Data:\n\n");
                                    sb.append(jsonObject.toString(2).replace("\\/","/"));
                                    retVal = sb.toString();
                                    return retVal;
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                    return retVal;
                                }
                            }
                        }
                    }
                }
            }
        }
        return retVal;
    }

    private String arrayToString(co.nstant.in.cbor.model.Array array) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("[ ");

        for (DataItem valueItem : array.getDataItems()) {
            if (valueItem.getMajorType() == MajorType.MAP)
                stringBuilder.append(mapToString((co.nstant.in.cbor.model.Map)valueItem));
            else
                stringBuilder.append(valueItem);

            stringBuilder.append(", ");
        }
        if (stringBuilder.toString().endsWith(", ")) {
            stringBuilder.setLength(stringBuilder.length() - 2);
        }
        stringBuilder.append(" ]");
        return stringBuilder.toString();
    }


    private String mapToString(co.nstant.in.cbor.model.Map map) {
        StringBuilder stringBuilder = new StringBuilder();
        if (map.isChunked()) {
            stringBuilder.append("{_ ");
        } else {
            stringBuilder.append("{ ");
        }
        for (DataItem key : map.getKeys()) {
            stringBuilder.append("\"").append(key).append("\": ");
            DataItem valueItem = map.get(key);
            if (valueItem.getMajorType() == MajorType.MAP)
                stringBuilder.append(mapToString((co.nstant.in.cbor.model.Map)valueItem));
            else if (valueItem.getMajorType() == MajorType.ARRAY)
                stringBuilder.append(arrayToString((co.nstant.in.cbor.model.Array)valueItem));
            else {
                String value = valueItem.toString();
                try {
                    stringBuilder.append(Integer.parseInt(value));
                } catch (NumberFormatException ex) {
                    stringBuilder.append("\"").append(TextUtils.htmlEncode(value)).append("\"");
                }
            }
            stringBuilder.append(", ");
        }
        if (stringBuilder.toString().endsWith(", ")) {
            stringBuilder.setLength(stringBuilder.length() - 2);
        }
        stringBuilder.append(" }");

        return stringBuilder.toString();
    }

   private String translate(JSONObject jsonObject) {
        StringBuilder sb = new StringBuilder();
        sb.append("Issuer: <b>");
        try {
            sb.append(jsonObject.getString("1"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        sb.append("<br/></b>Issued at: <b>");
        try {
            Date issued = new Date(jsonObject.getLong("6") * 1000);
            SimpleDateFormat jdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
            jdf.setTimeZone(TimeZone.getDefault());
            sb.append(jdf.format(issued));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        sb.append("<br/></b>Expires at: <b>");
        try {
            Date expires = new Date(jsonObject.getLong("4") * 1000);
            SimpleDateFormat jdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
            jdf.setTimeZone(TimeZone.getDefault());
            sb.append(jdf.format(expires));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            JSONObject jsonObject1 = jsonObject.getJSONObject("-260").getJSONObject("1");
            sb.append("<br/></b>Version: <b>");
            try {
                sb.append(jsonObject1.getString("ver"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            sb.append("<br/></b>Date of Birth: <b>");
            try {
                sb.append(jsonObject1.getString("dob"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            try {
                JSONObject jsonObjectNam = jsonObject1.getJSONObject("nam");
                sb.append("<br/></b>Surname: <b>");
                try {
                    sb.append(jsonObjectNam.getString("fn"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                sb.append("<br/></b>Forname: <b>");
                try {
                    sb.append(jsonObjectNam.getString("gn"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                sb.append("<br/></b>ICAO 9303 Surname: <b>");
                try {
                    sb.append(jsonObjectNam.getString("fnt"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                sb.append("<br/></b>ICAO 9303 Forename: <b>");
                try {
                    sb.append(jsonObjectNam.getString("gnt"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
            if (jsonObject1.has("v")) {
                try {
                    JSONArray jsonArray = jsonObject1.getJSONArray("v");
                    for (int index = 0; index < jsonArray.length(); index++) {
                        JSONObject jsonObjectV = jsonArray.getJSONObject(index);
                        sb.append("<br/></b>Disease/Agent targeted: <b>");
                        try {
                            sb.append(jsonObjectV.getString("tg"));
                            if (jsonObjectV.getString("tg").equals("840539006")) {
                                sb.append("<br/>\t</b>D/A name: <b>COVID-19");
                            } else {
                                sb.append("<br/>\t</b>D/A name: <b>Unknown");
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Vaccine/Prophylaxis: <b>");
                        try {
                            sb.append(jsonObjectV.getString("vp"));
                            if (jsonObjectV.getString("vp").equals("1119305005")) {
                                sb.append("<br/>\t</b>V/P name: <b>SARS-CoV2 antigen vaccine");
                            }
                            else if (jsonObjectV.getString("vp").equals("1119349007")) {
                                sb.append("<br/>\t</b>V/P name: <b>SARS-CoV2 mRNA vaccine");
                            }
                            else if (jsonObjectV.getString("vp").equals("J07BX03")) {
                                    sb.append("<br/>\t</b>V/P name: <b>covid-19 vaccines");
                            }
                            else {
                                sb.append("<br/>\t</b>V/P name: <b>Unknown");
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Vaccine Medical Product: <b>");
                        try {
                            sb.append(jsonObjectV.getString("mp"));
                            if (jsonObjectV.getString("mp").equals("EU/1/20/1528")) {
                                sb.append("<br/>\t</b>VMP name: <b>Comirnaty");
                            } else if (jsonObjectV.getString("mp").equals("EU/1/20/1525")) {
                                sb.append("<br/>\t</b>VMP name: <b>Janssen");
                            } else if (jsonObjectV.getString("mp").equals("EU/1/20/1507")) {
                                sb.append("<br/>\t</b>VMP name: <b>Spikevax");
                            } else if (jsonObjectV.getString("mp").equals("EU/1/21/1529")) {
                                sb.append("<br/>\t</b>VMP name: <b>Vaxzevria");
                            } else {
                                sb.append("<br/>\t</b>VMP name: <b>Unknown");
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Marketing Authorization Holder - if no MAH present,<br/>then manufacturer: <b>");
                        try {
                            sb.append(jsonObjectV.getString("ma"));
                            if (jsonObjectV.getString("ma").equals("ORG-100001699")) {
                                sb.append("<br/>\t</b>MAH name: <b>AstraZeneca AB");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100030215")) {
                                sb.append("<br/>\t</b>MAH name: <b>Biontech Manufacturing GmbH");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100001417")) {
                                sb.append("<br/>\t</b>MAH name: <b>Janssen-Cilag International");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100031184")) {
                                sb.append("<br/>\t</b>MAH name: <b>Moderna Biotech Spain S.L.");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100006270")) {
                                sb.append("<br/>\t</b>MAH name: <b>Curevac AG");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100013793")) {
                                sb.append("<br/>\t</b>MAH name: <b>CanSino Biologics");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100020693")) {
                                sb.append("<br/>\t</b>MAH name: <b>China Sinopharm International Corp. - Beijing location");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100010771")) {
                                sb.append("<br/>\t</b>MAH name: <b>Sinopharm Weiqida Europe Pharmaceutical s.r.o. - Prague location");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100024420")) {
                                sb.append("<br/>\t</b>MAH name: <b>Sinopharm Zhijun (Shenzhen) Pharmaceutical Co. Ltd. - Shenzhen location");
                            } else if (jsonObjectV.getString("ma").equals("ORG-100032020")) {
                                sb.append("<br/>\t</b>MAH name: <b>Novavax CZ AS");
                            } else {
                                sb.append("<br/>\t</b>MAH name: <b>Unknown");
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Dose Number: <b>");
                        try {
                            sb.append(jsonObjectV.getInt("dn"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Total Series of Doses: <b>");
                        try {
                            sb.append(jsonObjectV.getInt("sd"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Country of Vaccination: <b>");
                        try {
                            sb.append(jsonObjectV.getString("co"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Certificate Issuer: <b>");
                        try {
                            sb.append(jsonObjectV.getString("is"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Unique Certificate Identifier:<br/> <b>");
                        try {
                            sb.append(jsonObjectV.getString("ci"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }

                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            if (jsonObject1.has("t")) {
                try {
                    JSONArray jsonArray = jsonObject1.getJSONArray("t");
                    for (int index = 0; index < jsonArray.length(); index++) {
                        JSONObject jsonObjectV = jsonArray.getJSONObject(index);
                        sb.append("<br/></b>Disease/Agent targeted: <b>");
                        try {
                            sb.append(jsonObjectV.getString("tg"));
                            if (jsonObjectV.getString("tg").equals("840539006")) {
                                sb.append("<br/>\t</b>D/A name: <b>COVID-19");
                            }
                            else {
                                sb.append("<br/>\t</b>D/A name: <b>Unknown");
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Type of Test: <b>");
                        try {
                            sb.append(jsonObjectV.getString("tt"));
                            if (jsonObjectV.getString("tt").equals("LP6464-4")) {
                                sb.append("<br/>\t</b>Test Type: <b>Nucleic acid amplification with probe detection");
                            } else if (jsonObjectV.getString("tt").equals("LP217198-3")) {
                                sb.append("<br/>\t</b>Test Type: <b>Rapid immunoassay");
                            } else {
                                sb.append("<br/>\t</b>Test Type: <b>Unknown");
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }

                        if (jsonObjectV.has("nm")) {
                            sb.append("<br/></b>NAA Test Name: <b>");
                            try {
                                sb.append(jsonObjectV.getString("nm"));
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                        if (jsonObjectV.has("ma")) {
                            sb.append("<br/></b>RAT Test name and manufacturer: <b>");
                            try {
                                sb.append(jsonObjectV.getString("ma"));
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                        sb.append("<br/></b>Date/Time of Sample Collection: <b>");
                        try {
                            sb.append(jsonObjectV.getString("sc"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Test Result: <b>");
                        try {
                            sb.append(jsonObjectV.getString("tr"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Testing Centre: <b>");
                        try {
                            sb.append(jsonObjectV.getString("tc"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Country of Test: <b>");
                        try {
                            sb.append(jsonObjectV.getString("co"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Certificate Issuer: <b>");
                        try {
                            sb.append(jsonObjectV.getString("is"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Unique Certificate Identifier:<br/> <b>");
                        try {
                            sb.append(jsonObjectV.getString("ci"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            if (jsonObject1.has("r")) {
                try {
                    JSONArray jsonArray = jsonObject1.getJSONArray("r");
                    for (int index = 0; index < jsonArray.length(); index++) {
                        JSONObject jsonObjectV = jsonArray.getJSONObject(index);
                        sb.append("<br/></b>Disease/Agent targeted: <b>");
                        try {
                            sb.append(jsonObjectV.getString("tg"));
                            if (jsonObjectV.getString("tg").equals("840539006")) {
                                sb.append("<br/>\t</b>D/A name: <b>COVID-19");
                            }
                            else {
                                sb.append("<br/>\t</b>D/A name: <b>Unknown");
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>ISO 8601 complete date of first positive NAA test result:<br/>\t<b>");
                        try {
                            sb.append(jsonObjectV.getString("fr"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Country of Test: <b>");
                        try {
                            sb.append(jsonObjectV.getString("co"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Certificate Issuer: <b>");
                        try {
                            sb.append(jsonObjectV.getString("is"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>ISO 8601 complete date: Certificate Valid From:<br/>\t<b>");
                        try {
                            sb.append(jsonObjectV.getString("df"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>ISO 8601 complete date: Certificate Valid Until:<br/>\t<b>");
                        try {
                            sb.append(jsonObjectV.getString("du"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        sb.append("<br/></b>Unique Certificate Identifier:<br/> <b>");
                        try {
                            sb.append(jsonObjectV.getString("ci"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        sb.append("</b>");

        return sb.toString();
    }

}