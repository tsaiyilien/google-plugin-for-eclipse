/*******************************************************************************
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.google.gdt.eclipse.core.update.internal.core;

import org.eclipse.core.resources.IProject;

/**
 * Interface for UpdateQueryArgContributor extension point.
 */
public interface UpdateQueryArgContributor {

  /**
   * @return The update query argument contribution. This must be in the form
   *         "&isSignedIn=true" or the empty string to contribute nothing
   */
  String getContribution(IProject project);

}
