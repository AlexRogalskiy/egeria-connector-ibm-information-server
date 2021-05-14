/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.ibm.ia.clientlibrary.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SuggestionType implements IAEnum {

    ALL("all");

    @JsonValue
    private String value;
    SuggestionType(String value) { this.value = value; }

    @Override
    public String getValue() { return value; }

}
