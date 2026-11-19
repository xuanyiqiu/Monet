package dev.monet.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

/** Email + password sign-in. Stays on screen until the session flow reports SignedIn. */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val c = Monet.colors
    val focus = LocalFocusManager.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink,
        focusedBorderColor = c.seal,
        unfocusedBorderColor = c.hairline,
        cursorColor = c.seal,
        focusedLabelColor = c.seal,
        unfocusedLabelColor = c.inkMuted,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.paper)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(Spacing.x6))
        Text("Sign in to Monet", style = Monet.type.display, color = c.ink)
        Spacer(Modifier.height(Spacing.x2))
        Text(
            "Your account keeps your notebook and documents with you. You only sign in once on this device.",
            style = Monet.type.body,
            color = c.inkMuted,
        )
        Spacer(Modifier.height(Spacing.x6))
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmail,
            label = { Text("Email") },
            singleLine = true,
            enabled = !state.busy,
            shape = RoundedCornerShape(Radius.sm),
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.x3))
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            label = { Text("Password") },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(Radius.sm),
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focus.clearFocus()
                onSubmit()
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) {
            Spacer(Modifier.height(Spacing.x3))
            Text(
                state.error,
                style = Monet.type.body,
                color = c.danger,
                modifier = Modifier.semantics { contentDescription = "Error: ${state.error}" },
            )
        }
        Spacer(Modifier.height(Spacing.x6))
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            shape = RoundedCornerShape(Radius.sm),
            colors = ButtonDefaults.buttonColors(containerColor = c.seal, contentColor = c.paper),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    color = c.paper,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .height(20.dp)
                        .semantics { contentDescription = "Signing in" },
                )
            } else {
                Text("Sign in", style = Monet.type.label)
            }
        }
        Spacer(Modifier.height(Spacing.x6))
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LoginLight() = MonetTheme(darkTheme = false) {
    LoginScreen(LoginUiState(email = "you@example.com"), {}, {}, {})
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LoginDarkError() = MonetTheme(darkTheme = true) {
    LoginScreen(LoginUiState(email = "you@example.com", password = "x", error = "Wrong email or password."), {}, {}, {})
}
