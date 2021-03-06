/*******************************************************************************
 * Copyright (c) 2019, 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.adapter.lora;

/**
 * Constants used in the lora adapter.
 */
public class LoraConstants {

    /**
     * The name of the AMQP 1.0 message application property containing the name of the LoRa protocol provider over
     * which an uploaded message has originally been received.
     */
    public static final String APP_PROPERTY_ORIG_LORA_PROVIDER = "orig_lora_provider";
    public static final String FIELD_PSK = "psk";
    public static final String FIELD_VIA = "via";
    public static final String FIELD_AUTH_ID = "auth-id";
    public static final String FIELD_LORA_CONFIG = "lora-network-server";
    public static final String FIELD_LORA_URL = "url";
    public static final String FIELD_LORA_PROVIDER = "provider";
    public static final String FIELD_LORA_VENDOR_PROPERTIES = "vendor-properties";
    public static final String FIELD_LORA_CREDENTIAL_IDENTITY = "identity";
    public static final String FIELD_LORA_CREDENTIAL_KEY = "key";
    public static final String FIELD_LORA_DEVICE_PORT = "lora-port";
    public static final String FIELD_LORA_DOWNLINK_PAYLOAD = "payload";
    public static final String EMPTY = "";
    public static final String CONTENT_TYPE_LORA_POST_FIX = "+json";
    public static final String CONTENT_TYPE_LORA_BASE = "application/vnd.eclipse-hono.lora.";
    public static final String NORMALIZED_PROPERTIES = "normalized_properties";
    public static final String ADDITIONAL_DATA = "additional_data";
    public static final String APP_PROPERTY_RSS = "rss";
    public static final String APP_PROPERTY_TX_POWER = "tx_power";
    public static final String APP_PROPERTY_CHANNEL = "channel";
    public static final String APP_PROPERTY_SUB_BAND = "sub_band";
    public static final String APP_PROPERTY_SPREADING_FACTOR = "spreading_factor";
    public static final String APP_PROPERTY_BANDWIDTH = "bandwidth";
    public static final String APP_PROPERTY_SNR = "snr";
    public static final String APP_PROPERTY_FUNCTION_PORT = "function_port";
    public static final String APP_PROPERTY_FUNCTION_LATITUDE = "latitude";
    public static final String APP_PROPERTY_FUNCTION_LONGITUDE = "longitude";
    public static final String GATEWAYS = "gateways";
    public static final String GATEWAY_ID = "gateway_id";
    public static final String DATA_RATE = "data_rate";
    public static final String CODING_RATE = "coding_rate";
    public static final String FREQUENCY = "frequency";
    public static final String FRAME_COUNT = "frame_count";

    private LoraConstants() {
        // prevent instantiation
    }
}
