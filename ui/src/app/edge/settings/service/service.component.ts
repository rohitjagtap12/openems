import { ActivatedRoute } from '@angular/router';
import { Component } from '@angular/core';
import { Edge, EdgeConfig, Service, Utils, Websocket } from '../../../shared/shared';
import { ModalController, PopoverController } from '@ionic/angular';
import { TranslateService } from '@ngx-translate/core';
import { HeatingElementRTUComponent } from './heatingelementrtu/heatingelementrtu.component';
import { HeatingElementTCPComponent } from './heatingelementtcp/heatingelementtcp.component';
import { EVCSComponent } from './evcs/evcs.component';
import { HeatingpumpTCPComponent } from './heatingpumptcp/heatingpumptcp.component';

@Component({
  selector: ServiceComponent.SELECTOR,
  templateUrl: './service.component.html'
})
export class ServiceComponent {

  private static readonly SELECTOR = "service";

  public edge: Edge = null;
  public config: EdgeConfig = null;

  public factory: EdgeConfig.Factory = null;

  constructor(
    private route: ActivatedRoute,
    private service: Service,
    public modalCtrl: ModalController,
    private translate: TranslateService,
  ) { }

  ngOnInit() {
    this.service.setCurrentComponent(this.translate.instant('Edge.Config.Index.service'), this.route).then(edge => {
      this.edge = edge;
    });
    this.service.getConfig().then(config => {
      this.config = config;
    });
  }

  public async presentModalHeatingRTU() {
    const modal = await this.modalCtrl.create({
      component: HeatingElementRTUComponent,
      componentProps: {
        edge: this.edge,
      }
    });
    return await modal.present();
  }

  public async presentModalHeatingTCP() {
    const modal = await this.modalCtrl.create({
      component: HeatingElementTCPComponent,
      componentProps: {
        edge: this.edge,
      }
    });
    return await modal.present();
  }

  public async presentModalEVCS() {
    const modal = await this.modalCtrl.create({
      component: EVCSComponent,
      componentProps: {
        edge: this.edge,
      }
    });
    return await modal.present();
  }

  public async presentModalHeatingpump() {
    const modal = await this.modalCtrl.create({
      component: HeatingpumpTCPComponent,
      componentProps: {
        edge: this.edge,
      }
    });
    return await modal.present();
  }
}