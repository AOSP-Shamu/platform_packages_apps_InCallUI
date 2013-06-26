/*
 * Copyright (C) 2013 The Android Open Source Project
 *
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
 */

package com.android.incallui;

import android.graphics.Bitmap;

public class CallerInfoCookie {
    public String name;
    public String number;
    public String typeofnumber;
    public Bitmap photo;
    public int presentation;
    public CallerInfoCookie() {}
    public CallerInfoCookie(String name, String number, String typeofnumber,
            Bitmap photo, int presentation) {
        this.name = name;
        this.number = number;
        this.typeofnumber = typeofnumber;
        this.photo = photo;
        this.presentation = presentation;
    }
    public void clean() {
        name = null;
        number = null;
        typeofnumber = null;
        photo = null;
        presentation = 0;
    }
}