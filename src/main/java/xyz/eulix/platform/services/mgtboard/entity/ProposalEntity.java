package xyz.eulix.platform.services.mgtboard.entity;

import lombok.*;
import xyz.eulix.platform.services.support.model.BaseEntity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.validation.constraints.NotNull;


@Getter
@Setter
@ToString(callSuper = true)
@Entity
@Table(name = "proposal")
@AllArgsConstructor(staticName = "of")
@NoArgsConstructor
public class ProposalEntity extends BaseEntity {

    @NotNull
    @Column(name = "content")
    private String content;

    @Column(name = "email")
    private String email;

    @Column(name = "phone_numer")
    private String phoneNumer;

    @Column(name = "image_urls")
    private String imageUrls;
}
