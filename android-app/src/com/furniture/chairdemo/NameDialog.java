package com.furniture.chairdemo;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

final class NameDialog {
    interface Save { void run(String name) throws Exception; }

    static void show(Activity activity, String title, String initial, String hint, String dismiss, boolean allowBlank, Save save) {
        StudioDialog dialog = StudioDialog.panel(activity,title,StudioIcon.Kind.EDIT);
        dialog.body.addView(Ui.text(activity,allowBlank?"名称（可选）":"名称",13,Ui.MUTED));
        Ui.space(dialog.body,6);
        EditText input = Ui.field(activity,initial,hint,InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSelectAllOnFocus(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        dialog.body.addView(input);
        TextView error = Ui.text(activity,"",13,Ui.DANGER);
        error.setPadding(0,Ui.dp(activity,6),0,0);error.setVisibility(View.GONE);dialog.body.addView(error);
        Runnable submit = () -> {
            try {
                save.run(LibraryNames.validate(input.getText().toString(),allowBlank));
                dialog.dismiss();
            } catch (Exception e) {
                error.setText(e.getMessage());error.setVisibility(View.VISIBLE);
            }
        };
        dialog.actions(dismiss,null,"保存",submit,false);
        dialog.setOnShowListener(d -> {input.requestFocus();input.selectAll();});
        input.setOnEditorActionListener((v,action,event) -> {
            if(action!=EditorInfo.IME_ACTION_DONE)return false;
            submit.run();return true;
        });
        dialog.show();
    }
}
