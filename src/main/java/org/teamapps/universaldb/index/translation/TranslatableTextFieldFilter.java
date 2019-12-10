/*-
 * ========================LICENSE_START=================================
 * UniversalDB
 * ---
 * Copyright (C) 2014 - 2019 TeamApps.org
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.teamapps.universaldb.index.translation;

import org.teamapps.universaldb.index.text.TextFilterType;

public class TranslatableTextFieldFilter extends TranslatableTextFilter {

    private final String fieldName;

    public static TranslatableTextFieldFilter create(TranslatableTextFilter filter, String fieldName) {
        return new TranslatableTextFieldFilter(filter.getFilterType(), fieldName, filter.getValue(), filter.getLanguage());
    }

    public TranslatableTextFieldFilter(TextFilterType filterType, String fieldName, String value, String language) {
        super(filterType, value, language);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }
}