package com.geostamp.camera.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.geostamp.camera.model.PhotoItem

/**
 * Edit a captured photo's stamp (location, date & time, note) and re-render it from the
 * unstamped source kept in [com.geostamp.camera.data.EditStore].
 *
 * STUB: implemented by the edit-feature work. Signature is the contract used by Navigation.
 */
@Composable
fun PhotoEditScreen(
    photo: PhotoItem,
    onBack: () -> Unit,
    onSaved: (PhotoItem) -> Unit,
    modifier: Modifier = Modifier
) {
}
