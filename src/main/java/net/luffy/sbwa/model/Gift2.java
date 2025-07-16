package net.luffy.sbwa.model;

import cn.hutool.core.io.FileUtil;
import net.luffy.sbwa.util.Common;

import java.io.File;
import java.io.InputStream;

public class Gift2 implements Gift {
    public final String lottery_id;
    public final String id;
    public final String name;
    public final String pic;
    public final String quality;
    public final int index;
    public final File picFile;

    public Gift2(String lotteryId, String id, String name, String pic, String quality, int index) {
        this.lottery_id = lotteryId;
        this.id = id;
        this.name = name;
        this.pic = pic;
        this.quality = quality;
        this.index = index;
        this.picFile = new File(new File(Common.I.picFolder, lotteryId), pic);
    }

    @Override
    public InputStream getPic() {
        if (this.picFile.exists()) {
            return FileUtil.getInputStream(this.picFile);
        }
        return null;
    }

    @Override
    public String getTitle() {
        return String.format("[%s]%s", this.quality, this.name);
    }

    public OwnedGift owned() {
        return new OwnedGift(this.picFile, getTitle());
    }
}
