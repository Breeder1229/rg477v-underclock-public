package com.anbernic.control.underclock.shared;

/** Exact, bounded settings messages. No paths, shell syntax or arbitrary resource IDs. */
public final class CustomProfileCommand {
    public final int displayIndex;
    public final CustomProfileValues values;

    private CustomProfileCommand(int index, CustomProfileValues row) { displayIndex=index; values=row; }

    public static CustomProfileCommand parse(String raw) {
        if(raw==null || raw.length()>70 || !raw.matches("[0-9]{1,20}:[0-7](?::[0-9]{1,10}){0,4}"))
            throw new IllegalArgumentException("프로필 요청 형식을 확인할 수 없습니다.");
        String[] fields=raw.split(":",-1);
        int index=fields[1].charAt(0)-'0';
        if(fields.length==2) return new CustomProfileCommand(index,null);
        if(fields.length!=6 || index<4) throw new IllegalArgumentException("Custom 요청은 주파수 네 개가 필요합니다.");
        int[] row=new int[4];
        try { for(int i=0;i<4;i++) row[i]=Integer.parseInt(fields[i+2]); }
        catch(NumberFormatException failure) { throw new IllegalArgumentException("주파수 숫자 범위를 벗어났습니다.",failure); }
        return new CustomProfileCommand(index,CustomProfileValues.of(row));
    }

    public static String selection(String nonce,int index) {
        String raw=nonce+":"+index;parse(raw);return raw;
    }
    public static String custom(String nonce,int slot,CustomProfileValues values) {
        if(slot<0||slot>3||values==null)throw new IllegalArgumentException("Custom 슬롯 확인 실패");
        String raw=nonce+":"+(slot+4)+":"+values.encode().replace(',',':');parse(raw);return raw;
    }
}
