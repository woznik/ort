/*
 * Copyright (C) 2020 Bosch.IO GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.utils.storage

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream

import java.io.File

/**
 * A [FileStorage] that stores compressed files in a [directory] of the local file system.
 */
class XZCompressedLocalFileStorage(
    /**
     * The directory used to store files in.
     */
    directory: File
) : LocalFileStorage(directory) {
    override fun read(path: String) = XZCompressorInputStream(super.read("$path.xz"))

    override fun getOutputStream(path: String) = XZCompressorOutputStream(super.getOutputStream("$path.xz"))
}